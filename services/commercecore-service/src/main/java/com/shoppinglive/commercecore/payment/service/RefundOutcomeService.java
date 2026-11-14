package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.RefundExecution;
import com.shoppinglive.commercecore.payment.entity.MockRefundOutcome;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 작업자가 영속 실행 권한을 보유한 동안 확인된 결과를 원자적으로 반영한다. */
@Service
public class RefundOutcomeService {
    private final com.shoppinglive.commercecore.coupon.service.CouponReservationService coupons;
    private final com.shoppinglive.commercecore.order.service.OrderSheetRefundService sheetRefunds;
    private final JdbcTemplate jdbc;
    private final PaymentDeliveryStore deliveries;
    private final PaymentGroupRepository groups;
    private final com.shoppinglive.commercecore.sku.service.SkuService skus;
    private final PaymentGroupEventPublisher notificationEvents;
    @PersistenceContext private EntityManager entityManager;

    public RefundOutcomeService(
            JdbcTemplate jdbc,
            PaymentDeliveryStore deliveries,
            PaymentGroupRepository groups,
            com.shoppinglive.commercecore.sku.service.SkuService skus,
            PaymentGroupEventPublisher notificationEvents,
            com.shoppinglive.commercecore.coupon.service.CouponReservationService coupons,
            com.shoppinglive.commercecore.order.service.OrderSheetRefundService sheetRefunds) {
        this.jdbc = jdbc;
        this.coupons = coupons;
        this.sheetRefunds = sheetRefunds;
        this.deliveries = deliveries;
        this.groups = groups;
        this.skus = skus;
        this.notificationEvents = notificationEvents;
    }

    @Transactional
    public boolean apply(RefundExecution result) {
        PaymentGroup group = groups.lockById(result.paymentGroupId()).orElseThrow();
        entityManager.refresh(group);
        var delivery = deliveries.get(result.requestId());
        if (delivery.terminal()) {
            deliveries.requireSameResult(delivery, result);
            return false;
        }
        var requests =
                jdbc.query(
                        "SELECT id,payment_group_id,refund_amount,status FROM refund_request WHERE id=? FOR UPDATE",
                        (rs, row) ->
                                new RefundExecutionRequest(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getLong(3),
                                        RefundStatus.valueOf(rs.getString(4))),
                        result.coreRefundRequestId());
        if (requests.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "환불 요청이 없습니다.");
        var request = requests.getFirst();
        if (request.paymentGroupId() != result.paymentGroupId()
                || request.refundAmount() != result.amount()
                || !fitsPaymentAmount(group.getPayableAmount(), request))
            throw new BusinessException(ErrorCode.CONFLICT, "환불 요청 대상과 금액이 다릅니다.");
        if (group.isOrderSheet()
                && "USER"
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT purpose FROM refund_request WHERE id=?",
                                        String.class,
                                        result.coreRefundRequestId()))) {
            boolean applied = sheetRefunds.apply(result, group);
            deliveries.record(delivery, result, result.status().name());
            return applied;
        }
        boolean applied =
                applyOutcome(
                        result,
                        MockRefundOutcome.valueOf(result.status().name()),
                        "Mock 환불 결과: " + result.status());
        deliveries.record(delivery, result, result.status().name());
        return applied;
    }

    private boolean fitsPaymentAmount(long paymentAmount, RefundExecutionRequest current) {
        Long previousAmount =
                jdbc.queryForObject(
                        """
            SELECT COALESCE(SUM(refund_amount), 0) FROM refund_request
             WHERE payment_group_id=? AND id<>? AND status IN ('PROCESSING','UNKNOWN','SUCCESS')
            """,
                        Long.class,
                        current.paymentGroupId(),
                        current.id());
        try {
            return Math.addExact(
                            previousAmount == null ? 0L : previousAmount, current.refundAmount())
                    <= paymentAmount;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean applyOutcome(RefundExecution result, MockRefundOutcome outcome, String detail) {
        long requestId = result.coreRefundRequestId();
        if ("COMPENSATION"
                .equals(
                        jdbc.queryForObject(
                                "SELECT purpose FROM refund_request WHERE id=?",
                                String.class,
                                requestId))) {
            if (jdbc.update(
                            "UPDATE refund_request SET status=?,resolved_at=?,result_detail=?,retry_count=?,next_action_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PROCESSING','UNKNOWN')",
                            outcome.name(),
                            java.sql.Timestamp.from(result.resolvedAt()),
                            detail,
                            result.retryCount(),
                            java.sql.Timestamp.from(result.nextActionAt()),
                            requestId)
                    != 1) throw new IllegalStateException("보상 환불 결과 적용 상태가 다릅니다.");
            return true;
        }

        if (outcome == MockRefundOutcome.SUCCESS) {
            List<RefundTarget> targets =
                    jdbc.query(
                            """
                SELECT t.order_id,COALESCE(o.sku_id,o.sales_info_id),o.quantity
                  FROM refund_target_order t JOIN orders o ON o.id=t.order_id
                 WHERE t.refund_request_id=? ORDER BY COALESCE(o.sku_id,o.sales_info_id),o.id
                """,
                            (rs, row) ->
                                    new RefundTarget(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                            requestId);
            for (RefundTarget target : targets) {
                int transitioned =
                        jdbc.update(
                                """
                    UPDATE orders SET status='REFUNDED',version=version+1,updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND status='PAID'
                    """,
                                target.orderId());
                if (transitioned != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "환불 대상 주문 상태가 변경되었습니다.");
                }
                skus.restock(target.salesId(), target.quantity());
            }
            Long groupId =
                    jdbc.queryForObject(
                            "SELECT payment_group_id FROM refund_request WHERE id=?",
                            Long.class,
                            requestId);
            Long remaining =
                    jdbc.queryForObject(
                            """
                SELECT COUNT(*) FROM orders WHERE payment_group_id=? AND status<>'REFUNDED'
                """,
                            Long.class,
                            groupId);
            if (remaining != null && remaining == 0) {
                PaymentGroup group =
                        groups.lockById(groupId)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
                group.transition(OrderStatus.REFUNDED);
                if (group.isOrderSheet())
                    coupons.restoreForOrder(
                            group.getMemberId(), group.getCouponId(), group.getId());
            }
        }
        int updated =
                jdbc.update(
                        """
            UPDATE refund_request
               SET status=?, resolved_at=?, result_detail=?, updated_at=CURRENT_TIMESTAMP,
                   retry_count=?,next_action_at=?
             WHERE id=? AND status IN ('PROCESSING','UNKNOWN')
            """,
                        outcome.name(),
                        java.sql.Timestamp.from(result.resolvedAt()),
                        detail,
                        result.retryCount(),
                        java.sql.Timestamp.from(result.nextActionAt()),
                        requestId);
        if (updated != 1) {
            throw new IllegalStateException("환불 결과 반영 중 요청 상태가 변경되었습니다.");
        }
        if (outcome == MockRefundOutcome.SUCCESS) {
            notificationEvents.refunded(result.paymentGroupId(), requestId, result.amount());
        }
        return true;
    }

    private record RefundTarget(long orderId, long salesId, int quantity) {}

    private record RefundExecutionRequest(
            long id, long paymentGroupId, long refundAmount, RefundStatus status) {}
}
