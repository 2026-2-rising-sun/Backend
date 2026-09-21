package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Mock 결제 엔진 (결제 2).
 *
 * <p>결제 시작 시 호출되어 시나리오의 delay 만큼 후에 {@link PaymentService#resolvePayment}
 * 를 호출한다. INSTANT 시나리오는 즉시 async 호출.
 *
 * <p><b>순환 참조 회피:</b> PaymentService → MockPaymentEngine → PaymentService 순환을 피하기
 * 위해 {@link ObjectProvider} 로 지연 lookup.
 *
 * <p><b>재기동 안전:</b> 이 엔진은 in-process 스케줄러라 JVM 재기동 시 예약된 콜백을 잃는다.
 * 결제 3 이슈의 PaymentDelayReconciler 가 DB 스캔 fallback 을 제공.
 */
@Component
public class MockPaymentEngine {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentEngine.class);

    private final ObjectProvider<PaymentService> paymentServiceProvider;
    private final ScheduledExecutorService scheduler;

    public MockPaymentEngine(ObjectProvider<PaymentService> paymentServiceProvider) {
        this.paymentServiceProvider = paymentServiceProvider;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "mock-payment-engine");
            t.setDaemon(true);
            return t;
        });
    }

    public void schedule(Long paymentAttemptId, PaymentScenario scenario) {
        Runnable resolve = () -> {
            try {
                paymentServiceProvider.getObject().resolvePayment(paymentAttemptId);
            } catch (Exception e) {
                log.warn("mock resolve failed for attemptId={}: {}", paymentAttemptId, e.toString());
            }
        };
        long delayMs = scenario.getDelay().toMillis();
        if (delayMs == 0) {
            scheduler.submit(resolve);
        } else {
            scheduler.schedule(resolve, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
