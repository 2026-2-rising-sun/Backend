package com.shoppinglive.commerce.payment.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** DB에 저장한 처리 예정 시각으로 실행 재시도와 재시작 후 결과 조회 중심의 복구를 제어한다. */
@Component
public class PaymentDelayReconciler {
    private static final Logger log = LoggerFactory.getLogger(PaymentDelayReconciler.class);
    private final PaymentRecoveryStore recovery;
    private final PaymentService service;
    private final boolean enabled;

    public PaymentDelayReconciler(
            PaymentRecoveryStore recovery,
            PaymentService service,
            @Value("${commerce.payment.recovery-enabled:true}") boolean enabled) {
        this.recovery = recovery;
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT0.25S")
    public void runScheduled() {
        if (enabled) {
            reconcileOverdue();
        }
    }

    public int reconcileOverdue() {
        int count = 0;
        for (int i = 0; i < 100; i++) {
            // 다음 항목 하나만 실행 권한을 확보해 대기 중인 작업의 권한 유효 기간이 먼저 소진되지 않도록 한다.
            var leases = recovery.claimDue(1, Duration.ofMinutes(1));
            if (leases.isEmpty()) {
                break;
            }
            var lease = leases.getFirst();
            try {
                if (service.resolveClaimed(lease)) {
                    count++;
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "payment recovery failed: attemptId={}, will retry when due",
                        lease.id(),
                        failure);
            }
        }
        return count;
    }
}
