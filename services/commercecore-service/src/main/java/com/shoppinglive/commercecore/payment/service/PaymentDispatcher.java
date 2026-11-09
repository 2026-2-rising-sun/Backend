package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.payment.dto.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PaymentDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PaymentDispatcher.class);
    private final PaymentDeliveryStore store;
    private final PaymentClient client;
    private final PaymentResultHandler outcomes;
    private final boolean enabled;
    private final boolean refundsEnabled;

    public PaymentDispatcher(
            PaymentDeliveryStore store,
            PaymentClient client,
            PaymentResultHandler outcomes,
            @Value("${commercecore.payment.delivery-recovery-enabled:true}") boolean enabled,
            @Value("${commercecore.refunds.execution-enabled:false}") boolean refundsEnabled) {
        this.store = store;
        this.client = client;
        this.outcomes = outcomes;
        this.enabled = enabled;
        this.refundsEnabled = refundsEnabled;
    }

    public PaymentExecution paymentAfterCommit(long id) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            com.shoppinglive.commercecore.support.AfterCommit.run(
                    () ->
                            java.util.concurrent.CompletableFuture.runAsync(
                                    () -> {
                                        try {
                                            payment(id);
                                        } catch (RuntimeException failure) {
                                            log.warn(
                                                    "커밋 후 Payment 전달 실패: paymentId={}",
                                                    id,
                                                    failure);
                                        }
                                    }));
            return store.payment(id);
        }
        return payment(id);
    }

    public PaymentExecution payment(long id) {
        dispatch(store.byPayment(id));
        return store.payment(id);
    }

    public boolean refund(long id) {
        return dispatch(store.byRefund(id));
    }

    public boolean dispatch(PaymentDeliveryStore.Delivery d) {
        if (d.terminal() || ("REFUND".equals(d.kind()) && !refundsEnabled)) return false;
        try {
            // 같은 원문을 재전달한다. Payment가 requestId 유일키로 응답 유실과 프로세스 중단을 복구한다.
            if ("PAYMENT".equals(d.kind()))
                return outcomes.payment(
                        client.start(store.read(d.command(), PaymentCommand.class)));
            else
                return outcomes.refund(client.refund(store.read(d.command(), RefundCommand.class)));
        } catch (org.springframework.web.client.RestClientException unavailable) {
            store.delay(d);
            log.warn("Payment 전달 재시도 예정: requestId={}", d.requestId());
            return false;
        }
    }

    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT1S")
    public void recover() {
        if (enabled)
            for (var d : store.due(refundsEnabled))
                try {
                    dispatch(d);
                } catch (RuntimeException failure) {
                    store.delay(d);
                    log.warn("Payment 결과 복구 실패: requestId={}", d.requestId(), failure);
                }
    }
}
