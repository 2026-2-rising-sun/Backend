package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.payment.service.*;
import java.time.Duration;

public class RefundBoundaryReconciler {
    private final RefundRecoveryStore recovery;
    private final RefundBoundaryTestDriver executions;

    public RefundBoundaryReconciler(
            RefundRecoveryStore recovery, RefundBoundaryTestDriver executions) {
        this.recovery = recovery;
        this.executions = executions;
    }

    public int reconcileDue() {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            var leases = recovery.claimDue(1, Duration.ofMinutes(1));
            if (leases.isEmpty()) break;
            try {
                if (executions.executeClaimed(leases.getFirst(), MockRefundScenario.SUCCESS))
                    count++;
            } catch (RuntimeException failed) {
            }
        }
        return count + executions.recoverPending();
    }
}
