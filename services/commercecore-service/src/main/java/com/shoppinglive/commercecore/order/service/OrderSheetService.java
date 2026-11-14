package com.shoppinglive.commercecore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.service.CartSelectionPort;
import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderAmounts;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.product.service.ProductTradingService;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderSheetService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
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
    private final com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events;

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
            com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events) {
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
        this.events = events;
    }

    public Creation create(String member, String key, CreateOrderSheetRequest request) {
        if (key == null || key.isBlank() || key.length() > 64) throw invalid("생성 키는 1~64자여야 합니다.");
        if (request == null) throw invalid("주문서 입력이 필요합니다.");
        if (request.items() != null
                && request.items().stream()
                        .anyMatch(
                                i ->
                                        i == null
                                                || i.skuId() == null
                                                || i.skuId() < 1
                                                || i.quantity() < 1))
            throw invalid("유효한 SKU와 수량이 필요합니다.");
        if (request.cartItems() != null
                && request.cartItems().stream()
                        .anyMatch(
                                i ->
                                        i == null
                                                || i.itemId() == null
                                                || i.itemId() < 1
                                                || i.version() == null
                                                || i.version() < 0))
            throw invalid("유효한 장바구니 선택이 필요합니다.");
        boolean fromCart = request.cartItems() != null && !request.cartItems().isEmpty();
        boolean direct = request.items() != null && !request.items().isEmpty();
        if (fromCart == direct) throw invalid("SKU 목록 또는 장바구니 선택 목록 중 하나만 필요합니다.");
        if (request.buyerName() == null
                || request.buyerName().isBlank()
                || request.buyerPhone() == null
                || request.buyerPhone().isBlank()) throw invalid("구매자 정보가 필요합니다.");
        var directItems =
                direct
                        ? request.items().stream()
                                .sorted(
                                        Comparator.comparing(
                                                CreateOrderSheetRequest.Item::skuId,
                                                Comparator.nullsFirst(Comparator.naturalOrder())))
                                .toList()
                        : List.<CreateOrderSheetRequest.Item>of();
        var selections =
                fromCart
                        ? request.cartItems().stream()
                                .sorted(
                                        Comparator.comparing(
                                                CartSelection::itemId,
                                                Comparator.nullsFirst(Comparator.naturalOrder())))
                                .toList()
                        : List.<CartSelection>of();
        String fingerprint =
                fingerprint(
                        List.of(
                                directItems,
                                selections,
                                request.buyerName(),
                                request.buyerPhone()));
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    var replay = groups.findByMemberIdAndRequestKey(member, key);
                    if (replay.isPresent()) {
                        if (!replay.get().isOrderSheet()
                                || !Objects.equals(replay.get().getFingerprint(), fingerprint))
                            throw conflict("다른 주문 요청에 사용된 생성 키입니다.");
                        if (!replay.get().getExpiresAt().isAfter(time.now()))
                            lifecycle.expire(replay.get().getId());
                        return new Creation(
                                response(groups.findById(replay.get().getId()).orElseThrow()),
                                false);
                    }
                    guard.requireNoActive(member);
                    List<Line> lines;
                    if (fromCart) {
                        lines =
                                cart.lockOwned(member, selections).stream()
                                        .map(
                                                c ->
                                                        new Line(
                                                                c.skuId(),
                                                                c.quantity(),
                                                                c.itemId(),
                                                                c.version()))
                                        .toList();
                    } else
                        lines =
                                directItems.stream()
                                        .map(i -> new Line(i.skuId(), i.quantity(), null, null))
                                        .toList();
                    Set<Long> ids = new HashSet<>();
                    for (var line : lines)
                        if (line.skuId() == null
                                || line.skuId() < 1
                                || line.quantity() < 1
                                || !ids.add(line.skuId()))
                            throw invalid("SKU와 수량은 양수이며 같은 SKU를 중복 선택할 수 없습니다.");
                    List<SkuView> read = skus.findBySkuIds(ids);
                    if (read.size() != ids.size())
                        throw new BusinessException(ErrorCode.NOT_FOUND, "SKU를 찾을 수 없습니다.");
                    var productSnapshots =
                            products
                                    .lockForNewOrder(read.stream().map(SkuView::productId).toList())
                                    .stream()
                                    .collect(
                                            Collectors.toMap(
                                                    p -> p.productId(), Function.identity()));
                    var locked =
                            skus.lockForOrder(ids).stream()
                                    .collect(Collectors.toMap(SkuView::skuId, Function.identity()));
                    long total = 0;
                    for (var line : lines) {
                        var sku = locked.get(line.skuId());
                        if (!productSnapshots.containsKey(sku.productId()))
                            throw conflict("SKU의 상품 연결이 변경되었습니다.");
                        try {
                            total =
                                    Math.addExact(
                                            total,
                                            OrderAmounts.total(sku.price(), line.quantity()));
                        } catch (ArithmeticException overflow) {
                            throw invalid("주문 금액이 허용 범위를 초과합니다.");
                        }
                    }
                    Instant created = time.now();
                    PaymentGroup group =
                            new PaymentGroup(
                                    UUID.randomUUID().toString(),
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
                    for (var line :
                            lines.stream().sorted(Comparator.comparing(Line::skuId)).toList()) {
                        var sku = locked.get(line.skuId());
                        var product = productSnapshots.get(sku.productId());
                        List<Long> legacy =
                                jdbc.queryForList(
                                        "SELECT id FROM sales_info WHERE id=? AND product_id=?",
                                        Long.class,
                                        sku.skuId(),
                                        sku.productId());
                        var item =
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
                    events.publish("PaymentGroupCreated", group.getId(), Map.of());
                    return new Creation(response(group), true);
                });
    }

    public OrderSheetResponse get(String member, String number) {
        PaymentGroup group = owned(member, number);
        if (!group.getExpiresAt().isAfter(time.now())) lifecycle.expire(group.getId());
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
