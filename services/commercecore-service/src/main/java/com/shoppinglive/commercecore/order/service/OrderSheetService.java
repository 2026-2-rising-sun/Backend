package com.shoppinglive.commercecore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.service.CartSelectionPort;
import com.shoppinglive.commercecore.coupon.service.CouponPreviewService;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderAmounts;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.product.dto.ProductTradeSnapshot;
import com.shoppinglive.commercecore.product.service.ProductTradingService;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderSheetService {
    @PersistenceContext private EntityManager entityManager;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PurchaseGuard guard;
    private final CartSelectionPort cart;
    private final ProductTradingService products;
    private final SkuService skus;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OrderSheetLifecycleService lifecycle;
    private final OrderTimeSource time;
    private final CouponPreviewService couponPreview;
    private final CouponReservationService coupons;
    private final PaymentGroupEventPublisher events;

    public OrderSheetService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PurchaseGuard guard,
            CartSelectionPort cart,
            ProductTradingService products,
            SkuService skus,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            OrderSheetLifecycleService lifecycle,
            OrderTimeSource time,
            CouponPreviewService couponPreview,
            CouponReservationService coupons,
            PaymentGroupEventPublisher events) {
        this.groups = groups;
        this.orders = orders;
        this.guard = guard;
        this.cart = cart;
        this.products = products;
        this.skus = skus;
        this.tx = tx;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.time = time;
        this.couponPreview = couponPreview;
        this.coupons = coupons;
        this.events = events;
    }

    public Creation create(String member, String key, CreateOrderSheetRequest request) {
        return create(member, key, request, null);
    }

    public record PaymentTerms(String couponId, Long couponSkuId, long expectedAmount) {}

    public Creation createForPayment(
            String member, String key, CreateOrderSheetRequest request, PaymentTerms terms) {
        if (terms == null || terms.expectedAmount() <= 0) {
            throw invalid("예상 실결제액은 양수여야 합니다.");
        }
        return create(member, key, request, terms);
    }

    private Creation create(
            String member, String key, CreateOrderSheetRequest request, PaymentTerms terms) {
        validateRequest(key, request);
        boolean fromCart = request.cartItems() != null && !request.cartItems().isEmpty();
        boolean direct = request.items() != null && !request.items().isEmpty();
        var directItems =
                direct
                        ? request.items().stream()
                                .sorted(Comparator.comparing(CreateOrderSheetRequest.Item::skuId))
                                .toList()
                        : List.<CreateOrderSheetRequest.Item>of();
        var selections =
                fromCart
                        ? request.cartItems().stream()
                                .sorted(Comparator.comparing(CartSelection::itemId))
                                .toList()
                        : List.<CartSelection>of();
        var fingerprintParts =
                new ArrayList<Object>(
                        List.of(
                                directItems,
                                selections,
                                request.buyerName(),
                                request.buyerPhone()));
        if (terms != null) {
            fingerprintParts.add(terms);
        }
        String fingerprint = fingerprint(fingerprintParts);
        // 중계의 외부 TX는 guard를 먼저 준비한다. 여기서 새 연결을 요구하지 않는다.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            guard.ensure(member);
        }
        return tx.execute(
                t -> {
                    guard.lock(member);
                    var replay = groups.findByMemberIdAndRequestKey(member, key);
                    if (replay.isPresent()) {
                        if (!replay.get().isOrderSheet()
                                || !Objects.equals(replay.get().getFingerprint(), fingerprint)) {
                            throw conflict("다른 주문 요청에 사용된 생성 키입니다.");
                        }
                        if (!replay.get().getExpiresAt().isAfter(time.now())) {
                            lifecycle.expire(replay.get().getId());
                        }
                        return new Creation(
                                response(groups.findById(replay.get().getId()).orElseThrow()),
                                false);
                    }
                    guard.requireNoActive(member);
                    List<Line> lines = selectedLines(member, fromCart, selections, directItems);
                    Set<Long> skuIds = selectedSkuIds(lines);
                    Map<Long, ProductTradeSnapshot> productSnapshots = lockProducts(skuIds);
                    Map<Long, SkuView> lockedSkus =
                            skus.lockForOrder(skuIds).stream()
                                    .collect(Collectors.toMap(SkuView::skuId, Function.identity()));
                    long total = totalAmount(lines, lockedSkus, productSnapshots);
                    PaymentGroup group = createGroup(member, key, fingerprint, total);
                    saveItemsAndReserve(
                            member, request, group, lines, lockedSkus, productSnapshots);
                    if (terms != null) {
                        applyPaymentTerms(group, terms);
                    }
                    events.publish("PaymentGroupCreated", group.getId(), Map.of());
                    return new Creation(response(group), true);
                });
    }

    private void validateRequest(String key, CreateOrderSheetRequest request) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw invalid("생성 키는 1~64자여야 합니다.");
        }
        if (request == null) {
            throw invalid("주문서 입력이 필요합니다.");
        }
        if (request.items() != null
                && request.items().stream()
                        .anyMatch(
                                i ->
                                        i == null
                                                || i.skuId() == null
                                                || i.skuId() < 1
                                                || i.quantity() < 1)) {
            throw invalid("유효한 SKU와 수량이 필요합니다.");
        }
        if (request.cartItems() != null
                && request.cartItems().stream()
                        .anyMatch(
                                i ->
                                        i == null
                                                || i.itemId() == null
                                                || i.itemId() < 1
                                                || i.version() == null
                                                || i.version() < 0)) {
            throw invalid("유효한 장바구니 선택이 필요합니다.");
        }
        boolean fromCart = request.cartItems() != null && !request.cartItems().isEmpty();
        boolean direct = request.items() != null && !request.items().isEmpty();
        if (fromCart == direct) {
            throw invalid("SKU 목록 또는 장바구니 선택 목록 중 하나만 필요합니다.");
        }
        if (request.buyerName() == null
                || request.buyerName().isBlank()
                || request.buyerPhone() == null
                || request.buyerPhone().isBlank()) {
            throw invalid("구매자 정보가 필요합니다.");
        }
    }

    private List<Line> selectedLines(
            String member,
            boolean fromCart,
            List<CartSelection> selections,
            List<CreateOrderSheetRequest.Item> directItems) {
        if (fromCart) {
            return cart.lockOwned(member, selections).stream()
                    .map(
                            item ->
                                    new Line(
                                            item.skuId(),
                                            item.quantity(),
                                            item.itemId(),
                                            item.version()))
                    .toList();
        }
        return directItems.stream()
                .map(item -> new Line(item.skuId(), item.quantity(), null, null))
                .toList();
    }

    private Set<Long> selectedSkuIds(List<Line> lines) {
        Set<Long> ids = new HashSet<>();
        for (Line line : lines) {
            if (line.skuId() == null
                    || line.skuId() < 1
                    || line.quantity() < 1
                    || !ids.add(line.skuId())) {
                throw invalid("SKU와 수량은 양수이며 같은 SKU를 중복 선택할 수 없습니다.");
            }
        }
        return ids;
    }

    private Map<Long, ProductTradeSnapshot> lockProducts(Set<Long> skuIds) {
        List<SkuView> selectedSkus = skus.findBySkuIds(skuIds);
        if (selectedSkus.size() != skuIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "SKU를 찾을 수 없습니다.");
        }
        return products
                .lockForNewOrder(selectedSkus.stream().map(SkuView::productId).toList())
                .stream()
                .collect(Collectors.toMap(ProductTradeSnapshot::productId, Function.identity()));
    }

    private long totalAmount(
            List<Line> lines,
            Map<Long, SkuView> lockedSkus,
            Map<Long, ProductTradeSnapshot> productSnapshots) {
        long total = 0;
        for (Line line : lines) {
            SkuView sku = lockedSkus.get(line.skuId());
            if (!productSnapshots.containsKey(sku.productId())) {
                throw conflict("SKU의 상품 연결이 변경되었습니다.");
            }
            try {
                total = Math.addExact(total, OrderAmounts.total(sku.price(), line.quantity()));
            } catch (ArithmeticException overflow) {
                throw invalid("주문 금액이 허용 범위를 초과합니다.");
            }
        }
        return total;
    }

    private PaymentGroup createGroup(String member, String key, String fingerprint, long total) {
        Instant created = time.now();
        PaymentGroup group =
                new PaymentGroup(
                        "PG-" + UUID.randomUUID(),
                        member,
                        key,
                        fingerprint,
                        total,
                        created.plusSeconds(600));
        group.useOrderSheetPolicy();
        groups.saveAndFlush(group);
        jdbc.update(
                "UPDATE payment_group SET created_at=?,updated_at=? WHERE id=?",
                java.sql.Timestamp.from(created),
                java.sql.Timestamp.from(created),
                group.getId());
        entityManager.refresh(group);
        return group;
    }

    private void saveItemsAndReserve(
            String member,
            CreateOrderSheetRequest request,
            PaymentGroup group,
            List<Line> lines,
            Map<Long, SkuView> lockedSkus,
            Map<Long, ProductTradeSnapshot> productSnapshots) {
        for (Line line : lines.stream().sorted(Comparator.comparing(Line::skuId)).toList()) {
            SkuView sku = lockedSkus.get(line.skuId());
            ProductTradeSnapshot product = productSnapshots.get(sku.productId());
            List<Long> legacy =
                    jdbc.queryForList(
                            "SELECT id FROM sales_info WHERE id=? AND product_id=?",
                            Long.class,
                            sku.skuId(),
                            sku.productId());
            Order item =
                    Order.sheetItem(
                            "OI-" + UUID.randomUUID(),
                            sku.skuId(),
                            legacy.isEmpty() ? null : legacy.getFirst(),
                            sku.productId(),
                            line.quantity(),
                            sku.price(),
                            product.name(),
                            product.sellerId(),
                            product.mainImageUrl(),
                            request.buyerName(),
                            request.buyerPhone(),
                            member,
                            group,
                            line.cartId(),
                            line.cartVersion());
            orders.saveAndFlush(item);
            skus.reserve(sku.skuId(), line.quantity());
        }
    }

    private void applyPaymentTerms(PaymentGroup group, PaymentTerms terms) {
        if (terms.couponId() == null) {
            if (terms.couponSkuId() != null) {
                throw invalid("쿠폰 없이 적용 SKU를 지정할 수 없습니다.");
            }
        } else {
            if (terms.couponSkuId() == null) {
                throw invalid("쿠폰 적용 SKU가 필요합니다.");
            }
            var item =
                    orders.findByPaymentGroupIdOrderByIdAsc(group.getId()).stream()
                            .filter(o -> Objects.equals(o.getSkuId(), terms.couponSkuId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("쿠폰 대상 SKU가 주문에 없습니다."));
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM coupon_definition WHERE id=? AND seller_id=?",
                            Integer.class,
                            terms.couponId(),
                            item.getSellerIdSnapshot())
                    != 1) {
                throw conflict("쿠폰과 상품의 판매자가 다릅니다.");
            }
            var quote =
                    couponPreview.previewAt(
                            group.getMemberId(),
                            terms.couponId(),
                            List.of(
                                    new CouponPreviewService.PricedItem(
                                            "selected",
                                            item.getProductIdSnapshot(),
                                            item.getTotalAmount())),
                            time.now());
            coupons.reserveAt(group.getMemberId(), terms.couponId(), time.now());
            jdbc.update(
                    "UPDATE orders SET discount_amount=?,payable_amount=total_amount-? WHERE id=?",
                    quote.discountAmount(),
                    quote.discountAmount(),
                    item.getId());
            group.selectCoupon(terms.couponId(), item.getId(), quote.discountAmount());
            groups.saveAndFlush(group);
            entityManager.refresh(item);
        }
        if (group.getPayableAmount() <= 0) {
            throw invalid("새 Payment 계약은 실결제액이 양수여야 합니다.");
        }
        if (group.getPayableAmount() != terms.expectedAmount()) {
            throw conflict("주문 실결제액이 변경되었습니다.");
        }
    }

    public OrderSheetResponse get(String member, String number) {
        PaymentGroup group = owned(member, number);
        if (!group.getExpiresAt().isAfter(time.now())) {
            lifecycle.expire(group.getId());
        }
        return response(owned(member, number));
    }

    PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .filter(PaymentGroup::isOrderSheet)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "주문서를 찾을 수 없습니다."));
    }

    OrderSheetResponse response(PaymentGroup group) {
        return OrderSheetResponse.from(
                group, orders.findByPaymentGroupIdOrderByIdAsc(group.getId()));
    }

    private String fingerprint(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception failure) {
            throw new IllegalStateException("주문 요청 비교값을 만들 수 없습니다.", failure);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private record Line(Long skuId, int quantity, Long cartId, Long cartVersion) {}

    public record Creation(OrderSheetResponse order, boolean created) {}
}
