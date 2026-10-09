package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recreates work from persisted due times, including requests whose process or callback was lost. */
@Component
@ConditionalOnProperty(prefix = "commerce.refunds", name = "execution-enabled", havingValue = "true")
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

    @Scheduled(initialDelayString = "${commerce.refunds.recovery.initial-delay:PT1S}",
        fixedDelayString = "${commerce.refunds.recovery.scan-delay:PT0.25S}")
    public void runScheduled() {
        int resolved = reconcileDue();
        if (resolved > 0) log.info("reconciled {} refund requests", resolved);
    }

    public int reconcileDue() {
        int resolved = 0;
        for (int scanned = 0; scanned < BATCH_SIZE; scanned++) {
            // Claim only the next request; a slow gateway cannot age leases for a queued batch.
            var next = recovery.claimDue(1, LEASE_DURATION);
            if (next.isEmpty()) break;
            var lease = next.getFirst();
            try {
                if (executions.executeClaimed(lease, MockRefundScenario.SUCCESS)) resolved++;
            } catch (RuntimeException failure) {
                log.warn("refund reconciliation failed for requestId={}; persisted work will be retried", lease.requestId(), failure);
            }
        }
        return resolved;
    }
}
