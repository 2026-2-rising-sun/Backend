package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.payments.infrastructure.PaymentRecoveryStore;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The durable due time controls execution retries and query-only recovery after restart. */
@Component
public class PaymentDelayReconciler {
    private static final Logger log=LoggerFactory.getLogger(PaymentDelayReconciler.class);
    private final PaymentRecoveryStore recovery;
    private final PaymentService service;
    private final boolean enabled;
    public PaymentDelayReconciler(PaymentRecoveryStore recovery,PaymentService service,
                                  @Value("${commerce.payment.recovery-enabled:true}") boolean enabled) {
        this.recovery=recovery;this.service=service;this.enabled=enabled;
    }
    @Scheduled(initialDelayString="PT1S",fixedDelayString="PT0.25S")
    public void runScheduled(){if(enabled)reconcileOverdue();}
    public int reconcileOverdue() {
        int count=0;
        for(int i=0;i<100;i++) {
            // Claim only the next item, so queued work does not use up its lease before execution.
            var leases=recovery.claimDue(1,Duration.ofMinutes(1));
            if(leases.isEmpty())break;
            var lease=leases.getFirst();
            try {if(service.resolveClaimed(lease))count++;}
            catch(RuntimeException failure){log.warn("payment recovery failed: attemptId={}, will retry when due",lease.id(),failure);}
        }
        return count;
    }
}
