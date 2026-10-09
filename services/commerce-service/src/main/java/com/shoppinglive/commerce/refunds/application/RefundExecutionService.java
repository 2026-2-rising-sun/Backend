package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.commerce.purchase.domain.PaymentGroup;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;
import com.shoppinglive.commerce.refunds.domain.MockRefundResult;
import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes one persisted refund request against the idempotent Mock gateway. */
@Service
public class RefundExecutionService {
    private final JdbcTemplate jdbc;
    private final PaymentGroupRepository groups;
    private final DurableMockRefundGateway gateway;
    private final SalesService sales;
    @PersistenceContext private EntityManager entityManager;

    public RefundExecutionService(JdbcTemplate jdbc, PaymentGroupRepository groups, DurableMockRefundGateway gateway,
        SalesService sales) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.gateway = gateway;
        this.sales = sales;
    }

    @Transactional
    public boolean execute(long refundRequestId) {
        return execute(refundRequestId, MockRefundScenario.SUCCESS);
    }

    @Transactional
    public boolean execute(long refundRequestId, MockRefundScenario scenario) {
        RefundExecutionRequest initial = findRequest(refundRequestId);
        if (initial == null) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");

        PaymentGroup group = groups.lockById(initial.paymentGroupId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        entityManager.refresh(group);

        RefundExecutionRequest request = findRequest(refundRequestId);
        if (request == null) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        if (request.status() != RefundStatus.PROCESSING && request.status() != RefundStatus.UNKNOWN) return false;

        if (!fitsPaymentAmount(group.getPayableAmount(), request)) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 금액을 초과하는 환불 요청입니다.");
        }

        if (request.refundAmount() == 0) {
            return applyOutcome(request.id(), MockRefundOutcome.SUCCESS, "0원 환불은 금전 실행 없이 처리합니다.");
        }

        try {
            MockRefundResult result = gateway.execute(request.id(), request.refundAmount(), scenario);
            return applyOutcome(request.id(), result.outcome(), "Mock 환불 결과: " + result.outcome());
        } catch (MockRefundResultUnknownException unknown) {
            jdbc.update("""
                UPDATE refund_request
                   SET status='UNKNOWN', result_detail=?, updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND status='PROCESSING'
                """, "Mock 환불 결과 확인이 필요합니다.", request.id());
            return false;
        }
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

    private boolean applyOutcome(long requestId, MockRefundOutcome outcome, String detail) {
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
               SET status=?, resolved_at=CURRENT_TIMESTAMP, result_detail=?, updated_at=CURRENT_TIMESTAMP
             WHERE id=? AND status IN ('PROCESSING','UNKNOWN')
            """, outcome.name(), detail, requestId);
        return updated == 1;
    }

    private record RefundTarget(long orderId, long salesId, int quantity) { }

    private RefundExecutionRequest findRequest(long requestId) {
        List<RefundExecutionRequest> rows = jdbc.query("""
            SELECT id,payment_group_id,refund_amount,status
              FROM refund_request WHERE id=?
            """, (rs, row) -> new RefundExecutionRequest(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                RefundStatus.valueOf(rs.getString(4))), requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record RefundExecutionRequest(long id, long paymentGroupId, long refundAmount, RefundStatus status) { }
}
