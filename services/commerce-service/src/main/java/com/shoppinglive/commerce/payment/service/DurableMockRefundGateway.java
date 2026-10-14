package com.shoppinglive.commerce.payment.service;

import com.shoppinglive.commerce.payment.dto.MockRefundResult;
import com.shoppinglive.commerce.payment.entity.MockRefundOutcome;
import com.shoppinglive.commerce.payment.exception.MockRefundResultUnknownException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 요청마다 변경할 수 없는 Mock 환불 결과 하나를 독립 트랜잭션에 저장한다. */
@Service
public class DurableMockRefundGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public DurableMockRefundGateway(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MockRefundResult execute(long refundRequestId, long refundAmount) {
        return execute(refundRequestId, refundAmount, MockRefundScenario.SUCCESS);
    }

    public MockRefundResult execute(
            long refundRequestId, long refundAmount, MockRefundScenario scenario) {
        if (refundRequestId <= 0 || refundAmount < 0 || scenario == null) {
            throw new IllegalArgumentException("환불 요청과 금액, 시나리오가 올바르지 않습니다.");
        }

        StoredExecution execution =
                independent.execute(
                        status -> {
                            MockRefundResult existing = find(refundRequestId);
                            if (existing != null) {
                                requireSameAmount(existing, refundAmount);
                                return new StoredExecution(existing, false);
                            }
                            if (scenario.unknownBeforeResult()) {
                                return null;
                            }

                            Instant recordedAt = Instant.now();
                            String reference = "MOCK-REFUND-" + refundRequestId;
                            int inserted =
                                    jdbc.update(
                                            """
                INSERT INTO mock_refund_result
                    (refund_request_id, refund_amount, outcome, refund_reference, recorded_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (refund_request_id) DO NOTHING
                """,
                                            refundRequestId,
                                            refundAmount,
                                            scenario.outcome().name(),
                                            reference,
                                            Timestamp.from(recordedAt));

                            MockRefundResult persisted = find(refundRequestId);
                            if (persisted == null) {
                                throw new IllegalStateException("Mock 환불 결과가 저장되지 않았습니다.");
                            }
                            requireSameAmount(persisted, refundAmount);
                            return new StoredExecution(persisted, inserted == 1);
                        });

        if (execution == null) {
            throw new MockRefundResultUnknownException(refundRequestId);
        }
        if (scenario.loseResponseAfterResult() && execution.created()) {
            throw new MockRefundResultUnknownException(refundRequestId);
        }
        return execution.result();
    }

    public MockRefundResult find(long refundRequestId) {
        List<MockRefundResult> results =
                jdbc.query(
                        """
            SELECT refund_request_id, refund_amount, outcome, refund_reference, recorded_at
            FROM mock_refund_result WHERE refund_request_id=?
            """,
                        (rs, row) ->
                                new MockRefundResult(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        MockRefundOutcome.valueOf(rs.getString(3)),
                                        rs.getString(4),
                                        rs.getTimestamp(5).toInstant()),
                        refundRequestId);
        return results.isEmpty() ? null : results.getFirst();
    }

    private static void requireSameAmount(MockRefundResult existing, long requestedAmount) {
        if (existing.refundAmount() != requestedAmount) {
            throw new IllegalStateException("환불 금액이 저장된 Mock 결과와 일치하지 않습니다.");
        }
    }

    private record StoredExecution(MockRefundResult result, boolean created) {}
}
