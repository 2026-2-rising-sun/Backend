package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;
import com.shoppinglive.commerce.refunds.domain.MockRefundResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists one immutable mock refund result per request in an independent transaction. */
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

    public MockRefundResult execute(long refundRequestId, long refundAmount, MockRefundScenario scenario) {
        if (refundRequestId <= 0 || refundAmount < 0 || scenario == null) {
            throw new IllegalArgumentException("refund request, amount, and scenario must be valid");
        }

        StoredExecution execution = independent.execute(status -> {
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
            int inserted = jdbc.update("""
                INSERT INTO mock_refund_result
                    (refund_request_id, refund_amount, outcome, refund_reference, recorded_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (refund_request_id) DO NOTHING
                """, refundRequestId, refundAmount, scenario.outcome().name(), reference, Timestamp.from(recordedAt));

            MockRefundResult persisted = find(refundRequestId);
            if (persisted == null) {
                throw new IllegalStateException("Mock refund result was not persisted");
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
        List<MockRefundResult> results = jdbc.query("""
            SELECT refund_request_id, refund_amount, outcome, refund_reference, recorded_at
            FROM mock_refund_result WHERE refund_request_id=?
            """, (rs, row) -> new MockRefundResult(rs.getLong(1), rs.getLong(2),
                MockRefundOutcome.valueOf(rs.getString(3)), rs.getString(4), rs.getTimestamp(5).toInstant()), refundRequestId);
        return results.isEmpty() ? null : results.getFirst();
    }

    private static void requireSameAmount(MockRefundResult existing, long requestedAmount) {
        if (existing.refundAmount() != requestedAmount) {
            throw new IllegalStateException("Refund amount differs from the persisted Mock result");
        }
    }

    private record StoredExecution(MockRefundResult result, boolean created) { }
}
