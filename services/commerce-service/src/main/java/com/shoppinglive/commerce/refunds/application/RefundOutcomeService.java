package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.commerce.purchase.domain.PaymentGroup;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore.Lease;
import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a known result atomically while the worker still owns the persisted lease. */
@Service
public class RefundOutcomeService {
    private final JdbcTemplate jdbc;
    private final PaymentGroupRepository groups;
    private final SalesService sales;
    @PersistenceContext private EntityManager entityManager;

    public RefundOutcomeService(JdbcTemplate jdbc, PaymentGroupRepository groups, SalesService sales) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.sales = sales;
    }

    @Transactional
    public boolean prepare(Lease lease) {
        return lockAndValidate(lease) != null;
    }

    @Transactional
    public boolean apply(Lease lease, MockRefundOutcome outcome) {
        RefundExecutionRequest request = lockAndValidate(lease);
        if (request == null) return false;
        return applyOutcome(lease, outcome, "Mock 환불 결과: " + outcome);
    }

    private RefundExecutionRequest lockAndValidate(Lease lease) {
        PaymentGroup group = groups.lockById(lease.paymentGroupId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        entityManager.refresh(group);
        List<RefundExecutionRequest> rows = jdbc.query("""
            SELECT id,payment_group_id,refund_amount,status FROM refund_request
             WHERE id=? AND lease_token=? AND lease_until > clock_timestamp()
               AND status IN ('PROCESSING','UNKNOWN') FOR UPDATE
            """, (rs, row) -> new RefundExecutionRequest(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                RefundStatus.valueOf(rs.getString(4))), lease.requestId(), lease.token());
        if (rows.isEmpty()) return null;
        RefundExecutionRequest request = rows.getFirst();
        if (request.paymentGroupId() != lease.paymentGroupId() || request.refundAmount() != lease.refundAmount()
            || !fitsPaymentAmount(group.getPayableAmount(), request)) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 금액을 초과하거나 변경된 환불 요청입니다.");
        }
        return request;
    }

    private boolean fitsPaymentAmount(long paymentAmount, RefundExecutionRequest current) {
        Long previousAmount = jdbc.queryForObject("""
            SELECT COALESCE(SUM(refund_amount), 0) FROM refund_request
             WHERE payment_group_id=? AND id<>? AND status IN ('PROCESSING','UNKNOWN','SUCCESS')
            """, Long.class, current.paymentGroupId(), current.id());
        try {
            return Math.addExact(previousAmount == null ? 0L : previousAmount, current.refundAmount()) <= paymentAmount;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private boolean applyOutcome(Lease lease, MockRefundOutcome outcome, String detail) {
        long requestId = lease.requestId();
        if (outcome == MockRefundOutcome.SUCCESS) {
            List<RefundTarget> targets = jdbc.query("""
                SELECT t.order_id,o.sales_info_id,o.quantity
                  FROM refund_target_order t JOIN orders o ON o.id=t.order_id
                 WHERE t.refund_request_id=? ORDER BY o.sales_info_id,o.id
                """, (rs, row) -> new RefundTarget(rs.getLong(1), rs.getLong(2), rs.getInt(3)), requestId);
            for (RefundTarget target : targets) {
                int transitioned = jdbc.update("""
                    UPDATE orders SET status='REFUNDED',version=version+1,updated_at=CURRENT_TIMESTAMP
                     WHERE id=? AND status='PAID'
                    """, target.orderId());
                if (transitioned != 1) {
                    throw new BusinessException(ErrorCode.CONFLICT, "환불 대상 주문 상태가 변경되었습니다.");
                }
                sales.adjustAvailable(target.salesId(), target.quantity());
            }
            Long groupId = jdbc.queryForObject("SELECT payment_group_id FROM refund_request WHERE id=?", Long.class, requestId);
            Long remaining = jdbc.queryForObject("""
                SELECT COUNT(*) FROM orders WHERE payment_group_id=? AND status<>'REFUNDED'
                """, Long.class, groupId);
            if (remaining != null && remaining == 0) {
                PaymentGroup group = groups.lockById(groupId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
                group.transition(OrderStatus.REFUNDED);
            }
        }
        int updated = jdbc.update("""
            UPDATE refund_request
               SET status=?, resolved_at=CURRENT_TIMESTAMP, result_detail=?, updated_at=CURRENT_TIMESTAMP,
                   lease_token=NULL,lease_until=NULL
             WHERE id=? AND status IN ('PROCESSING','UNKNOWN')
               AND lease_token=? AND lease_until > clock_timestamp()
            """, outcome.name(), detail, requestId, lease.token());
        if (updated != 1) throw new IllegalStateException("Refund lease expired during result application");
        return true;
    }

    private record RefundTarget(long orderId, long salesId, int quantity) { }

    private record RefundExecutionRequest(long id, long paymentGroupId, long refundAmount, RefundStatus status) { }
}
