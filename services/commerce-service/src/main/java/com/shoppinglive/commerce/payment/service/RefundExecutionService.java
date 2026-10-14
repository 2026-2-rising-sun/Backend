package com.shoppinglive.commerce.payment.service;

import com.shoppinglive.commerce.payment.dto.MockRefundResult;
import com.shoppinglive.commerce.payment.entity.MockRefundOutcome;
import com.shoppinglive.commerce.payment.exception.MockRefundResultUnknownException;
import com.shoppinglive.commerce.payment.service.RefundRecoveryStore.Lease;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** 짧은 유효 기간의 영속 실행 권한 확보, 외부 실행, 원자적인 결과 반영을 조율한다. */
@Service
public class RefundExecutionService {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    private final RefundRecoveryStore recovery;
    private final DurableMockRefundGateway gateway;
    private final RefundOutcomeService outcomes;
    private final RefundRetryPolicy retryPolicy;

    public RefundExecutionService(
            RefundRecoveryStore recovery,
            DurableMockRefundGateway gateway,
            RefundOutcomeService outcomes,
            RefundRetryPolicy retryPolicy) {
        this.recovery = recovery;
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.retryPolicy = retryPolicy;
    }

    public boolean execute(long requestId) {
        return execute(requestId, MockRefundScenario.SUCCESS);
    }

    public boolean execute(long requestId, MockRefundScenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("시나리오를 지정해야 합니다.");
        }
        if (!recovery.exists(requestId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        }
        var lease = recovery.claim(requestId, LEASE_DURATION);
        return lease.isPresent() && executeClaimed(lease.get(), scenario);
    }

    public boolean executeClaimed(Lease lease, MockRefundScenario scenario) {
        int currentRetryCount = lease.retryCount();
        try {
            if (!outcomes.prepare(lease)) {
                return false;
            }
            if (lease.refundAmount() == 0) {
                return outcomes.apply(lease, MockRefundOutcome.SUCCESS);
            }

            // 다시 실행하기 전에 이전 결과가 있는지 반드시 확인한다.
            MockRefundResult known = gateway.find(lease.requestId());
            if (known != null) {
                return apply(lease, known);
            }

            var invocation = recovery.beginInvocation(lease);
            if (invocation.isEmpty()) {
                recovery.unknown(lease, RefundRetryPolicy.RESULT_QUERY_DELAY);
                return false;
            }
            currentRetryCount = invocation.get();
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
            // 로컬 결과 반영이 롤백되어도 독립적으로 저장한 Mock 결과와 호출 횟수는 유지된다.
            recovery.release(
                    lease,
                    currentRetryCount == RefundRetryPolicy.MAX_RETRIES
                            ? RefundRetryPolicy.RESULT_QUERY_DELAY
                            : Duration.ofSeconds(1));
            throw failure;
        }
    }

    private boolean apply(Lease lease, MockRefundResult result) {
        if (result.refundRequestId() != lease.requestId()
                || result.refundAmount() != lease.refundAmount()) {
            throw new IllegalStateException("저장된 환불 결과가 원래 요청과 일치하지 않습니다.");
        }
        return outcomes.apply(lease, result.outcome());
    }
}
