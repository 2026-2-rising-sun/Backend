package com.shoppinglive.commercecore.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.payment.dto.RefundExecution;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.commercecore.payment.service.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderSheetRefundService {
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PurchaseGuard guard;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PaymentDeliveryStore deliveries;
    private final ObjectProvider<PaymentDispatcher> dispatcher;
    private final SkuService skus;
    private final CouponReservationService coupons;
    private final PaymentGroupEventPublisher events;
    private final OrderTimeSource time;
    private final ObjectMapper mapper;
    private final boolean executionEnabled;
    @PersistenceContext private EntityManager entityManager;

    public OrderSheetRefundService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PurchaseGuard guard,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            PaymentDeliveryStore deliveries,
            ObjectProvider<PaymentDispatcher> dispatcher,
            SkuService skus,
            CouponReservationService coupons,
            PaymentGroupEventPublisher events,
            OrderTimeSource time,
            ObjectMapper mapper,
            @Value("${commercecore.refunds.execution-enabled:false}") boolean executionEnabled) {
        this.groups = groups;
        this.orders = orders;
        this.guard = guard;
        this.jdbc = jdbc;
        this.tx = tx;
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.skus = skus;
        this.coupons = coupons;
        this.events = events;
        this.time = time;
        this.mapper = mapper;
        this.executionEnabled = executionEnabled;
    }

    public Creation request(
            String member, String number, String key, OrderSheetRefundRequest request) {
        if (key == null
                || key.isBlank()
                || key.length() > 64
                || request == null
                || request.items() == null
                || request.items().isEmpty()
                || request.items().size() > 100) throw invalid("환불 키와 1~100개 항목이 필요합니다.");
        var seen = new HashSet<Long>();
        for (var item : request.items())
            if (item == null
                    || item.orderItemId() == null
                    || item.orderItemId() < 1
                    || item.quantity() < 1
                    || !seen.add(item.orderItemId()))
                throw invalid("환불 항목/수량은 양수이고 항목이 중복되지 않아야 합니다.");
        var requested =
                request.items().stream()
                        .sorted(Comparator.comparing(OrderSheetRefundRequest.Item::orderItemId))
                        .toList();
        PaymentGroup found = owned(member, number);
        String fingerprint = fingerprint(found.getId(), requested);
        guard.ensure(member);
        Created saved =
                tx.execute(
                        t -> {
                            guard.lock(member);
                            PaymentGroup group = groups.lockById(found.getId()).orElseThrow();
                            entityManager.refresh(group);
                            var existing =
                                    jdbc.queryForList(
                                            "SELECT id,payment_group_id,request_fingerprint,purpose FROM refund_request WHERE member_id=? AND idempotency_key=?",
                                            member,
                                            key);
                            if (!existing.isEmpty()) {
                                var r = existing.getFirst();
                                if (!Objects.equals(r.get("payment_group_id"), group.getId())
                                        || !Objects.equals(
                                                r.get("request_fingerprint"), fingerprint)
                                        || !"USER".equals(r.get("purpose")))
                                    throw conflict("다른 환불 요청에 사용된 키입니다.");
                                return new Created(((Number) r.get("id")).longValue(), false);
                            }
                            if (group.getStatus() != OrderStatus.PAID
                                    && group.getStatus() != OrderStatus.PARTIALLY_REFUNDED)
                                throw conflict("결제 완료된 주문만 환불할 수 있습니다.");
                            if (jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM refund_request WHERE payment_group_id=? AND purpose='USER' AND status IN ('PROCESSING','UNKNOWN')",
                                            Long.class,
                                            group.getId())
                                    > 0) throw conflict("처리 중인 환불 결과를 먼저 확인해 주세요.");
                            var payment = deliveries.payment(group.getPaymentId());
                            if (payment.status()
                                            != com.shoppinglive.commercecore.payment.entity
                                                    .PaymentStatus.SUCCESS
                                    || payment.resolvedAt() == null
                                    || !RefundWindowPolicy.isOpen(time.now(), payment.resolvedAt()))
                                throw conflict("환불 가능한 결제 이력 또는 기간이 아닙니다.");
                            var targets = new ArrayList<Target>();
                            long total = 0;
                            for (var input : requested) {
                                Order item =
                                        orders.findById(input.orderItemId())
                                                .filter(
                                                        i ->
                                                                i.getPaymentGroup() != null
                                                                        && Objects.equals(
                                                                                i.getPaymentGroup()
                                                                                        .getId(),
                                                                                group.getId()))
                                                .orElseThrow(
                                                        () ->
                                                                new BusinessException(
                                                                        ErrorCode.NOT_FOUND,
                                                                        "주문의 환불 항목을 찾을 수 없습니다."));
                                var prior =
                                        jdbc.queryForMap(
                                                "SELECT COALESCE(SUM(t.quantity),0) AS quantity,COALESCE(SUM(t.refund_amount),0) AS amount FROM refund_target_order t JOIN refund_request r ON r.id=t.refund_request_id WHERE t.order_id=? AND r.status='SUCCESS' AND r.purpose='USER'",
                                                item.getId());
                                long count = ((Number) prior.get("quantity")).longValue();
                                long refunded = ((Number) prior.get("amount")).longValue();
                                if (input.quantity() > item.getQuantity() - count)
                                    throw conflict("구매 수량을 초과해 환불할 수 없습니다.");
                                long amount =
                                        OrderRefundAmounts.refund(
                                                item.getUnitPrice(),
                                                input.quantity(),
                                                item.getPayableAmount() - refunded);
                                try {
                                    total = Math.addExact(total, amount);
                                } catch (ArithmeticException overflow) {
                                    throw invalid("환불 금액이 허용 범위를 초과합니다.");
                                }
                                targets.add(new Target(item, input.quantity(), amount));
                            }
                            Instant at = time.now();
                            long amount = total;
                            var holder = new GeneratedKeyHolder();
                            jdbc.update(
                                    c -> {
                                        var s =
                                                c.prepareStatement(
                                                        "INSERT INTO refund_request(payment_group_id,member_id,idempotency_key,request_fingerprint,refund_amount,status,requested_at,created_at,updated_at,purpose) VALUES (?,?,?,?,?,'PROCESSING',?,?,?,'USER')",
                                                        new String[] {"id"});
                                        s.setLong(1, group.getId());
                                        s.setString(2, member);
                                        s.setString(3, key);
                                        s.setString(4, fingerprint);
                                        s.setLong(5, amount);
                                        s.setTimestamp(6, Timestamp.from(at));
                                        s.setTimestamp(7, Timestamp.from(at));
                                        s.setTimestamp(8, Timestamp.from(at));
                                        return s;
                                    },
                                    holder);
                            long id = Objects.requireNonNull(holder.getKey()).longValue();
                            for (var target : targets)
                                jdbc.update(
                                        "INSERT INTO refund_target_order(refund_request_id,order_id,product_id_snapshot,seller_id_snapshot,quantity,refund_amount,created_at) VALUES (?,?,?,?,?,?,?)",
                                        id,
                                        target.item().getId(),
                                        target.item().getProductIdSnapshot(),
                                        target.item().getSellerIdSnapshot(),
                                        target.quantity(),
                                        target.amount(),
                                        Timestamp.from(at));
                            deliveries.refund(
                                    id,
                                    payment.paymentId(),
                                    payment.orderId(),
                                    group.getId(),
                                    number,
                                    total);
                            return new Created(id, true);
                        });
        if (executionEnabled) dispatcher.getObject().refund(saved.id());
        return new Creation(get(member, number, saved.id()), saved.created());
    }

    public OrderSheetRefundResponse get(String member, String number, long id) {
        if (id < 1) throw invalid("환불 요청 ID는 양수여야 합니다.");
        PaymentGroup group = owned(member, number);
        var request =
                jdbc.queryForList(
                        "SELECT id,status,refund_amount,requested_at,resolved_at FROM refund_request WHERE id=? AND payment_group_id=? AND member_id=? AND purpose='USER'",
                        id,
                        group.getId(),
                        member);
        if (request.isEmpty())
            throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        var r = request.getFirst();
        var items =
                jdbc.query(
                        "SELECT t.order_id,o.sku_id,t.quantity,t.refund_amount FROM refund_target_order t JOIN orders o ON o.id=t.order_id WHERE t.refund_request_id=? ORDER BY t.order_id",
                        (rs, n) ->
                                new OrderSheetRefundResponse.Item(
                                        rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4)),
                        id);
        return new OrderSheetRefundResponse(
                id,
                number,
                (String) r.get("status"),
                ((Number) r.get("refund_amount")).longValue(),
                instant(r.get("requested_at")),
                r.get("resolved_at") == null ? null : instant(r.get("resolved_at")),
                items);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(RefundExecution result, PaymentGroup group) {
        if (group.getStatus() != OrderStatus.PAID
                && group.getStatus() != OrderStatus.PARTIALLY_REFUNDED)
            throw conflict("환불을 적용할 주문 상태가 아닙니다.");
        if (result.status() != RefundStatus.SUCCESS && result.status() != RefundStatus.FAILED)
            throw conflict("확정된 환불 결과가 필요합니다.");
        if (jdbc.update(
                        "UPDATE refund_request SET status=?,resolved_at=?,retry_count=?,next_action_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND payment_group_id=? AND purpose='USER' AND status IN ('PROCESSING','UNKNOWN')",
                        result.status().name(),
                        Timestamp.from(result.resolvedAt()),
                        result.retryCount(),
                        Timestamp.from(result.nextActionAt()),
                        result.coreRefundRequestId(),
                        group.getId())
                != 1) throw conflict("환불 적용 상태가 다릅니다.");
        if (result.status() == RefundStatus.FAILED) return true;
        var targets =
                jdbc.query(
                        "SELECT t.order_id,o.sku_id,t.quantity FROM refund_target_order t JOIN orders o ON o.id=t.order_id WHERE t.refund_request_id=? ORDER BY o.sku_id,o.id",
                        (r, n) -> new AppliedTarget(r.getLong(1), r.getLong(2), r.getInt(3)),
                        result.coreRefundRequestId());
        for (var target : targets) {
            long successful =
                    jdbc.queryForObject(
                            "SELECT COALESCE(SUM(t.quantity),0) FROM refund_target_order t JOIN refund_request r ON r.id=t.refund_request_id WHERE t.order_id=? AND r.status='SUCCESS' AND r.purpose='USER'",
                            Long.class,
                            target.itemId());
            int purchased =
                    jdbc.queryForObject(
                            "SELECT quantity FROM orders WHERE id=?",
                            Integer.class,
                            target.itemId());
            if (successful > purchased) throw conflict("성공 환불 수량이 구매 수량을 초과합니다.");
            if (jdbc.update(
                            "UPDATE orders SET status=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PAID','PARTIALLY_REFUNDED')",
                            successful == purchased ? "REFUNDED" : "PARTIALLY_REFUNDED",
                            target.itemId())
                    != 1) throw conflict("환불 항목의 상태가 다릅니다.");
            skus.restock(target.skuId(), target.quantity());
        }
        long remaining =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM orders WHERE payment_group_id=? AND status<>'REFUNDED'",
                        Long.class,
                        group.getId());
        group.transition(remaining == 0 ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED);
        if (remaining == 0)
            coupons.restoreForOrder(group.getMemberId(), group.getCouponId(), group.getId());
        groups.saveAndFlush(group);
        events.refunded(group.getId(), result.coreRefundRequestId(), result.amount());
        return true;
    }

    private PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .filter(PaymentGroup::isOrderSheet)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "주문서를 찾을 수 없습니다."));
    }

    private String fingerprint(long group, List<OrderSheetRefundRequest.Item> items) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(mapper.writeValueAsBytes(List.of(group, items))));
        } catch (Exception error) {
            throw new IllegalStateException("환불 요청 비교값을 만들 수 없습니다.", error);
        }
    }

    private static Instant instant(Object value) {
        return value instanceof java.time.OffsetDateTime d
                ? d.toInstant()
                : ((Timestamp) value).toInstant();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private record Target(Order item, int quantity, long amount) {}

    private record AppliedTarget(long itemId, long skuId, int quantity) {}

    private record Created(long id, boolean created) {}

    public record Creation(OrderSheetRefundResponse refund, boolean created) {}
}
