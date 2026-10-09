package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;
import com.shoppinglive.commerce.refunds.domain.MockRefundResult;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore.Lease;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Orchestrates short durable leases, external execution, and atomic result application. */
@Service
public class RefundExecutionService {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    private final RefundRecoveryStore recovery;
    private final DurableMockRefundGateway gateway;
    private final RefundOutcomeService outcomes;
    private final RefundRetryPolicy retryPolicy;

    public RefundExecutionService(RefundRecoveryStore recovery, DurableMockRefundGateway gateway,
                                  RefundOutcomeService outcomes, RefundRetryPolicy retryPolicy) {
        this.recovery = recovery;
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.retryPolicy = retryPolicy;
    }

    public boolean execute(long requestId) {
        return execute(requestId, MockRefundScenario.SUCCESS);
    }

    public boolean execute(long requestId, MockRefundScenario scenario) {
        if (scenario == null) throw new IllegalArgumentException("scenario must be provided");
        if (!recovery.exists(requestId)) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        var lease = recovery.claim(requestId, LEASE_DURATION);
        return lease.isPresent() && executeClaimed(lease.get(), scenario);
    }

    public boolean executeClaimed(Lease lease, MockRefundScenario scenario) {
        try {
            if (!outcomes.prepare(lease)) return false;
            if (lease.refundAmount() == 0) return outcomes.apply(lease, MockRefundOutcome.SUCCESS);

            // Always check an earlier result before considering another execution.
            MockRefundResult known = gateway.find(lease.requestId());
            if (known != null) return apply(lease, known);

            var invocation = recovery.beginInvocation(lease);
            if (invocation.isEmpty()) {
                recovery.unknown(lease, RefundRetryPolicy.RESULT_QUERY_DELAY);
                return false;
            }
            MockRefundResult result;
            try {
                result = gateway.execute(lease.requestId(), lease.refundAmount(), scenario);
            } catch (MockRefundResultUnknownException unknown) {
                recovery.unknown(lease, retryPolicy.afterUnknown(invocation.get()));
                return false;
            } catch (RuntimeException deliveryFailure) {
                recovery.unknown(lease, retryPolicy.afterUnknown(invocation.get()));
                throw deliveryFailure;
            }
            return apply(lease, result);
        } catch (RuntimeException failure) {
            // The independent Mock result and invocation count survive local application rollback.
            recovery.release(lease, Duration.ofSeconds(1));
            throw failure;
        }
    }

    private boolean apply(Lease lease, MockRefundResult result) {
        if (result.refundRequestId() != lease.requestId() || result.refundAmount() != lease.refundAmount()) {
            throw new IllegalStateException("Stored refund result differs from the original request");
        }
        return outcomes.apply(lease, result.outcome());
    }
}
