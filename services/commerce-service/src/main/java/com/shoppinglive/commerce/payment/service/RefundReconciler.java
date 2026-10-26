package com.shoppinglive.commerce.payment.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 저장된 처리 예정 시각을 기준으로 작업을 복구한다. 프로세스나 콜백이 유실된 요청도 포함한다. */
@Component
@ConditionalOnProperty(
        prefix = "commerce.refunds",
        name = "execution-enabled",
        havingValue = "true")
public class RefundReconciler {
    private static final Logger log = LoggerFactory.getLogger(RefundReconciler.class);
    private static final int BATCH_SIZE = 100;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    private final RefundRecoveryStore recovery;
    private final RefundExecutionService executions;

    public RefundReconciler(RefundRecoveryStore recovery, RefundExecutionService executions) {
        this.recovery = recovery;
        this.executions = executions;
    }

    @Scheduled(
            initialDelayString = "${commerce.refunds.recovery.initial-delay:PT1S}",
            fixedDelayString = "${commerce.refunds.recovery.scan-delay:PT0.25S}")
    public void runScheduled() {
        int resolved = reconcileDue();
        if (resolved > 0) {
            log.info("환불 요청 복구 완료: 복구 건수={}", resolved);
        }
    }

    public int reconcileDue() {
        int resolved = 0;
        for (int scanned = 0; scanned < BATCH_SIZE; scanned++) {
            // 다음 요청 하나만 실행 권한을 확보해 느린 게이트웨이 때문에 대기 중인 요청의 권한이 만료되지 않도록 한다.
            var next = recovery.claimDue(1, LEASE_DURATION);
            if (next.isEmpty()) {
                break;
            }
            var lease = next.getFirst();
            try {
                if (executions.executeClaimed(lease, MockRefundScenario.SUCCESS)) {
                    resolved++;
                }
            } catch (RuntimeException failure) {
                log.warn("환불 요청 복구 실패, 저장된 작업 재시도 예정: 환불 요청 ID={}", lease.requestId(), failure);
            }
        }
        return resolved;
    }
}
