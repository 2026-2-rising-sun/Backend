package com.shoppinglive.commercecore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.coupon.service.CouponPreviewService;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.dto.OrderResponse;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderAmounts;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.order.service.PaymentGroupService.Creation;
import com.shoppinglive.commercecore.order.service.PaymentGroupService.GroupResponse;
import com.shoppinglive.commercecore.order.service.PaymentGroupService.Item;
import com.shoppinglive.commercecore.order.service.PaymentGroupService.Quote;
import com.shoppinglive.commercecore.order.service.PaymentGroupService.Selection;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PaymentGroupCheckoutService {
    private static final List<OrderStatus> ACTIVE =
            List.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_CONFIRMING);
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final CartItemRepository cart;
    private final LegacyOrderSkuService trading;
    private final com.shoppinglive.commercecore.sku.service.SkuService skus;
    private final ProductLookup productLookup;
    private final PurchaseGuard guard;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OrderNumberGenerator numbers;
    private final CouponPreviewService couponPreview;
    private final CouponReservationService couponReservations;
    private final PaymentGroupEventPublisher notificationEvents;
    private final OrderTimeSource time;
    private final OrderSheetLifecycleService sheetLifecycle;
    @PersistenceContext private EntityManager entityManager;

    PaymentGroupCheckoutService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            CartItemRepository cart,
            LegacyOrderSkuService trading,
            com.shoppinglive.commercecore.sku.service.SkuService skus,
            ProductLookup productLookup,
            PurchaseGuard guard,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            OrderNumberGenerator numbers,
            CouponPreviewService couponPreview,
            CouponReservationService couponReservations,
            PaymentGroupEventPublisher notificationEvents,
            OrderTimeSource time,
            OrderSheetLifecycleService sheetLifecycle) {
        this.groups = groups;
        this.orders = orders;
        this.cart = cart;
        this.trading = trading;
        this.skus = skus;
        this.productLookup = productLookup;
        this.guard = guard;
        this.tx = tx;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.numbers = numbers;
        this.couponPreview = couponPreview;
        this.couponReservations = couponReservations;
        this.notificationEvents = notificationEvents;
        this.time = time;
        this.sheetLifecycle = sheetLifecycle;
    }

    Quote preview(String member, List<Selection> selections, String couponId) {
        List<Selection> selected = validateSelections(selections);
        List<Item> items = new ArrayList<>();
        long total = 0;
        for (Selection input : selected) {
            CartItem c =
                    cart.findByIdAndMemberId(input.itemId(), member)
                            .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다."));
            if (!Objects.equals(c.getVersion(), input.version())) {
                throw conflict("장바구니가 변경되었습니다. 다시 확인해 주세요.");
            }
            String name =
                    productLookup
                            .findProduct(c.getProductId())
                            .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다."))
                            .name();
            var s = trading.read(c.getProductId(), c.getSkuId());
            if (!s.status().canAcceptNewOrder() || s.available() < c.getQuantity()) {
                throw conflict("구매할 수 없는 상품 또는 재고 부족입니다.");
            }
            long amount = OrderAmounts.total(s.price(), c.getQuantity());
            total = add(total, amount);
            items.add(
                    new Item(
                            c.getId(),
                            c.getVersion(),
                            c.getProductId(),
                            name,
                            c.getQuantity(),
                            s.price(),
                            amount,
                            0L,
                            amount));
        }
        CouponPreviewService.Preview discount =
                couponPreview.preview(
                        member,
                        couponId,
                        items.stream()
                                .map(
                                        item ->
                                                new CouponPreviewService.PricedItem(
                                                        "cart:" + item.itemId(),
                                                        item.productId(),
                                                        item.totalAmount()))
                                .toList());
        List<Item> priced =
                items.stream()
                        .map(
                                item -> {
                                    long itemDiscount =
                                            discount.itemDiscounts().get("cart:" + item.itemId());
                                    return new Item(
                                            item.itemId(),
                                            item.version(),
                                            item.productId(),
                                            item.productName(),
                                            item.quantity(),
                                            item.unitPrice(),
                                            item.totalAmount(),
                                            itemDiscount,
                                            item.totalAmount() - itemDiscount);
                                })
                        .toList();
        return new Quote(
                List.copyOf(priced),
                total,
                discount.couponId(),
                discount.discountAmount(),
                discount.payableAmount());
    }

    CouponPreviewService.Preview previewSingle(
            String member, String couponId, long productId, long totalAmount) {
        return couponPreview.preview(
                member,
                couponId,
                List.of(new CouponPreviewService.PricedItem("single", productId, totalAmount)));
    }

    Creation create(
            String member,
            List<Selection> selections,
            String buyer,
            String phone,
            long expected,
            String couponId,
            String rawKey) {
        String key = "cart:" + fingerprint(rawKey).substring(0, 59);
        List<Selection> selected = validateSelections(selections);
        String fingerprint =
                couponId == null
                        ? fingerprint(List.of(selected, buyer, phone, expected))
                        : fingerprint(List.of(selected, buyer, phone, expected, couponId));
        Optional<PaymentGroup> existing = groups.findByMemberIdAndRequestKey(member, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        Quote quote = preview(member, selected, couponId);
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    Optional<PaymentGroup> replay = groups.findByMemberIdAndRequestKey(member, key);
                    if (replay.isPresent()) {
                        return replay(replay.get(), fingerprint);
                    }
                    guard.requireNoActive(member);
                    // 원본 행을 먼저 잠가 이후 결제 성공 처리가 중간에 수정된 항목을 삭제하지 않도록 한다.
                    for (Item item : quote.items()) {
                        CartItem current =
                                cart.lockOwned(item.itemId(), member)
                                        .orElseThrow(
                                                () ->
                                                        error(
                                                                ErrorCode.NOT_FOUND,
                                                                "장바구니 항목을 찾을 수 없습니다."));
                        entityManager.refresh(current);
                        if (!Objects.equals(current.getVersion(), item.version())) {
                            throw conflict("장바구니가 변경되었습니다.");
                        }
                    }
                    var selectedByProduct = new HashMap<Long, Long>();
                    for (Item item : quote.items())
                        selectedByProduct.put(
                                item.productId(),
                                cart.findById(item.itemId()).orElseThrow().getSkuId());
                    var currentSales =
                            trading.lockForNewOrder(selectedByProduct).stream()
                                    .collect(
                                            java.util.stream.Collectors.toMap(
                                                    com.shoppinglive.commercecore.sku.dto.SkuView
                                                            ::productId,
                                                    s -> s));
                    long total = 0;
                    for (Item item : quote.items()) {
                        var s = currentSales.get(item.productId());
                        if (!s.status().canAcceptNewOrder()) throw conflict("판매 중인 상품이 아닙니다.");
                        if (s.price() != item.unitPrice())
                            throw conflict("가격이 변경되었습니다. 주문서를 다시 확인해 주세요.");
                        total = add(total, OrderAmounts.total(s.price(), item.quantity()));
                    }
                    if (total != expected) {
                        throw conflict("주문 금액이 변경되었습니다. 다시 확인해 주세요.");
                    }
                    couponReservations.reserve(member, couponId);
                    PaymentGroup group =
                            groups.saveAndFlush(
                                    new PaymentGroup(
                                                    "PG-" + UUID.randomUUID(),
                                                    member,
                                                    key,
                                                    fingerprint,
                                                    total,
                                                    time.now().plusSeconds(600),
                                                    quote.discountAmount(),
                                                    couponId)
                                            .useLegacyDeadlinePolicy());
                    for (Item item : quote.items()) {
                        var s = currentSales.get(item.productId());
                        Order order =
                                new Order(
                                        numbers.generate(),
                                        s.skuId(),
                                        item.quantity(),
                                        item.unitPrice(),
                                        buyer,
                                        phone,
                                        member,
                                        item.productName(),
                                        null,
                                        group.getExpiresAt(),
                                        item.itemId(),
                                        expected,
                                        item.discountAmount());
                        order.recordSkuSource(s.skuId(), item.productId());
                        order.attachGroup(group, item.version());
                        orders.saveAndFlush(order);
                        skus.reserve(s.skuId(), item.quantity());
                    }
                    notificationEvents.publish("PaymentGroupCreated", group.getId(), Map.of());
                    return new Creation(
                            response(groups.findById(group.getId()).orElseThrow()), true);
                });
    }

    PaymentGroup bindSingle(
            Order order,
            String requestKey,
            Long sourceVersion,
            String couponId,
            long discountAmount) {
        String key = "single:" + (requestKey == null ? UUID.randomUUID() : requestKey);
        // 주문 멱등 키는 별도로 구성해 결제 묶음 키와 장바구니 요청 키가 충돌하지 않도록 한다.
        if (key.length() > 64) {
            key = "single:" + fingerprint(key).substring(0, 57);
        }
        couponReservations.reserve(order.getMemberId(), couponId);
        PaymentGroup group =
                groups.saveAndFlush(
                        new PaymentGroup(
                                        "PG-" + UUID.randomUUID(),
                                        order.getMemberId(),
                                        key,
                                        fingerprint(
                                                List.of(
                                                        order.getOrderNumber(),
                                                        couponId == null ? "" : couponId,
                                                        discountAmount)),
                                        order.getTotalAmount(),
                                        order.getExpiresAt(),
                                        discountAmount,
                                        couponId)
                                .useLegacyDeadlinePolicy());
        order.attachGroup(group, sourceVersion);
        orders.saveAndFlush(order);
        notificationEvents.publish("PaymentGroupCreated", group.getId(), Map.of());
        return group;
    }

    GroupResponse get(String member, String number) {
        PaymentGroup found = owned(member, number);
        if (found.enforcesDeadline()) sheetLifecycle.expire(found.getId());
        return tx.execute(t -> response(owned(member, number)));
    }

    Optional<GroupResponse> active(String member) {
        for (var group : groups.findByMemberIdAndStatusIn(member, ACTIVE)) {
            if (group.enforcesDeadline()) sheetLifecycle.expire(group.getId());
        }
        return tx.execute(
                t ->
                        groups.findByMemberIdAndStatusIn(member, ACTIVE).stream()
                                .findFirst()
                                .map(this::response));
    }

    private PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "주문 묶음을 찾을 수 없습니다."));
    }

    private GroupResponse response(PaymentGroup g) {
        return new GroupResponse(
                g.getGroupNumber(),
                g.getStatus(),
                g.getTotalAmount(),
                g.getDiscountAmount(),
                g.getPayableAmount(),
                g.getCouponId(),
                g.getExpiresAt(),
                orders.findByPaymentGroupIdOrderByIdAsc(g.getId()).stream()
                        .map(OrderResponse::from)
                        .toList(),
                g.getPaymentId());
    }

    private Creation replay(PaymentGroup g, String hash) {
        if (!g.getFingerprint().equals(hash)) {
            throw conflict("다른 주문 요청에 사용된 멱등키입니다.");
        }
        return new Creation(get(g.getMemberId(), g.getGroupNumber()), false);
    }

    private List<Selection> validateSelections(List<Selection> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 100) {
            throw error(ErrorCode.INVALID_REQUEST, "상품을 1~100개 선택해 주세요.");
        }
        Set<Long> seen = new HashSet<>();
        for (Selection s : inputs) {
            if (s == null
                    || s.itemId() == null
                    || s.itemId() < 1
                    || s.version() == null
                    || s.version() < 0
                    || !seen.add(s.itemId())) {
                throw error(ErrorCode.INVALID_REQUEST, "항목 ID·버전 또는 중복 선택이 올바르지 않습니다.");
            }
        }
        return inputs.stream().sorted(Comparator.comparing(Selection::itemId)).toList();
    }

    private String fingerprint(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(mapper.writeValueAsBytes(value)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw error(ErrorCode.INVALID_REQUEST, "주문 금액이 너무 큽니다.");
        }
    }

    private static BusinessException conflict(String message) {
        return error(ErrorCode.CONFLICT, message);
    }

    private static BusinessException error(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
