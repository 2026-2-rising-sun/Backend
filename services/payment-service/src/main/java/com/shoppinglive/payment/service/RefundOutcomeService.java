package com.shoppinglive.payment.service;

import com.shoppinglive.common.core.*;
import com.shoppinglive.payment.entity.MockRefundOutcome;
import com.shoppinglive.payment.service.RefundRecoveryStore.Lease;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundOutcomeService {
    private final JdbcTemplate jdbc;
    private final ExecutionResultPublisher events;

    public RefundOutcomeService(JdbcTemplate jdbc, ExecutionResultPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public boolean prepare(Lease lease) {
        return lockAndValidate(lease);
    }

    @Transactional
    public boolean apply(Lease lease, MockRefundOutcome outcome) {
        if (!lockAndValidate(lease)) return false;
        int changed =
                jdbc.update(
                        "UPDATE refund_execution SET status=?,resolved_at=clock_timestamp(),result_detail=?,updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND status IN ('PROCESSING','UNKNOWN')",
                        outcome.name(),
                        "Mock 환불 결과: " + outcome,
                        lease.requestId(),
                        lease.token());
        if (changed != 1) throw new IllegalStateException("환불 결과 반영 중 실행 권한이 만료되었습니다.");
        events.refund(RefundRows.get(jdbc, lease.requestId()));
        return true;
    }

    private boolean lockAndValidate(Lease lease) {
        // 환불 접수와 같은 잠금 순서(원결제 → 환불 실행)를 사용한다.
        var ids =
                jdbc.queryForList(
                        "SELECT payment_id FROM refund_execution WHERE id=?",
                        Long.class,
                        lease.requestId());
        if (ids.isEmpty()) return false;
        var payments =
                jdbc.queryForList(
                        "SELECT amount,status FROM payment_attempt WHERE payment_id=? FOR UPDATE",
                        ids.getFirst());
        if (payments.isEmpty() || !"SUCCESS".equals(payments.getFirst().get("status")))
            throw new BusinessException(ErrorCode.CONFLICT, "성공한 원결제가 필요합니다.");
        var requests =
                jdbc.queryForList(
                        "SELECT payment_group_id,refund_amount FROM refund_execution WHERE id=? AND lease_token=? AND lease_until>clock_timestamp() AND status IN ('PROCESSING','UNKNOWN') FOR UPDATE",
                        lease.requestId(),
                        lease.token());
        if (requests.isEmpty()) return false;
        var request = requests.getFirst();
        long cumulative =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(refund_amount),0) FROM refund_execution WHERE payment_id=? AND status IN ('PROCESSING','UNKNOWN','SUCCESS')",
                        Long.class,
                        ids.getFirst());
        if (((Number) request.get("payment_group_id")).longValue() != lease.paymentGroupId()
                || ((Number) request.get("refund_amount")).longValue() != lease.refundAmount()
                || cumulative > ((Number) payments.getFirst().get("amount")).longValue())
            throw new BusinessException(ErrorCode.CONFLICT, "결제 금액을 초과하거나 변경된 환불 요청입니다.");
        return true;
    }
}
