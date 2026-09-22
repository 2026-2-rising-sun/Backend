package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 지연 재확인 Reconciler (결제 3).
 *
 * <p>Mock 엔진의 in-process 스케줄러는 JVM 재기동으로 유실 가능. DB 에 남은
 * {@code status = PROCESSING} 이고 {@code scheduled_resolve_at} 이 지난 결제 시도를 주기적으로
 * 스캔해 {@link PaymentService#resolvePayment} 를 호출한다.
 *
 * <p>초기 지연 60 초로 테스트 컨텍스트에서 자동 실행되지 않게 한다. 재확인 주기는 10 초.
 *
 * <p><b>동시성:</b> 이 Reconciler 와 Mock 엔진이 같은 attempt 를 동시에 처리해도
 * {@link PaymentAttemptJpaRepository#resolveIfProcessing} 조건부 UPDATE 로 한쪽만 성공한다.
 */
@Component
public class PaymentDelayReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentDelayReconciler.class);
    private static final int BATCH_SIZE = 100;

    private final PaymentAttemptJpaRepository paymentAttemptRepository;
    private final PaymentService paymentService;

    public PaymentDelayReconciler(
        PaymentAttemptJpaRepository paymentAttemptRepository, PaymentService paymentService) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentService = paymentService;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10S")
    public void runScheduled() {
        int reconciled = reconcileOverdue();
        if (reconciled > 0) {
            log.info("reconciled {} overdue payment attempts", reconciled);
        }
    }

    /**
     * 지연 초과된 PROCESSING attempt 를 확정 처리한다. 테스트에서 직접 호출 가능.
     *
     * @return 실제로 확정된 attempt 수 (idempotent: 이미 다른 경로에서 확정된 것은 카운트되지 않음)
     */
    public int reconcileOverdue() {
        List<PaymentAttempt> overdue = paymentAttemptRepository
            .findByStatusAndScheduledResolveAtBefore(
                PaymentStatus.PROCESSING, Instant.now(), Limit.of(BATCH_SIZE));

        int count = 0;
        for (PaymentAttempt attempt : overdue) {
            // resolvePayment 는 idempotent. 조건부 UPDATE 로 이미 확정된 것은 자연스럽게 스킵.
            if (paymentService.resolvePayment(attempt.getId())) {
                count++;
            }
        }
        return count;
    }
}
