package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.common.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

public class HttpPaymentClient implements PaymentClient {
    private final RestClient http;

    public HttpPaymentClient(RestClient http) {
        this.http = http;
    }

    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Core DB 트랜잭션에서 Payment HTTP를 호출할 수 없습니다.");
    }

    public PaymentExecution start(PaymentCommand c) {
        outsideTransaction();
        return http.post()
                .uri("/v1/internal/payments")
                .body(c)
                .retrieve()
                .body(PaymentExecution.class);
    }

    public PaymentExecution get(long id) {
        outsideTransaction();
        return http.get()
                .uri("/v1/internal/payments/{id}", id)
                .retrieve()
                .body(PaymentExecution.class);
    }

    public RefundExecution refund(RefundCommand c) {
        outsideTransaction();
        return http.post()
                .uri("/v1/internal/refunds")
                .body(c)
                .retrieve()
                .body(RefundExecution.class);
    }

    public RefundExecution refund(long id) {
        outsideTransaction();
        return http.get()
                .uri("/v1/internal/refunds/{id}", id)
                .retrieve()
                .body(RefundExecution.class);
    }

    public void setScenario(
            String referenceNumber,
            com.shoppinglive.commercecore.payment.entity.PaymentScenario scenario) {
        outsideTransaction();
        http.put()
                .uri("/v1/internal/payment-scenarios/{number}", referenceNumber)
                .body(java.util.Map.of("scenario", scenario))
                .retrieve()
                .toBodilessEntity();
    }

    public void clearScenario(String referenceNumber) {
        outsideTransaction();
        http.delete()
                .uri("/v1/internal/payment-scenarios/{number}", referenceNumber)
                .retrieve()
                .toBodilessEntity();
    }
}
