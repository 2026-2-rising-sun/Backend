package com.shoppinglive.commercecore.payment.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 Payment 복구를 실행하고 HTTP 결과로 Core 사본을 복구한다. */
public class PaymentBoundaryReconciler {
    private final com.shoppinglive.payment.service.PaymentDelayReconciler scanner;
    private final PaymentDispatcher dispatcher;
    private final PaymentDeliveryStore store;
    private final JdbcTemplate jdbc;

    public PaymentBoundaryReconciler(
            com.shoppinglive.payment.service.PaymentDelayReconciler scanner,
            PaymentDispatcher dispatcher,
            PaymentDeliveryStore store,
            JdbcTemplate jdbc) {
        this.scanner = scanner;
        this.dispatcher = dispatcher;
        this.store = store;
        this.jdbc = jdbc;
    }

    public int reconcileOverdue() {
        scanner.reconcileOverdue();
        int count = 0;
        for (Long id :
                jdbc.queryForList(
                        "SELECT payment_id FROM payment_delivery_request WHERE kind='PAYMENT' AND status IN ('PROCESSING','UNKNOWN')",
                        Long.class))
            try {
                if (dispatcher.payment(id).status().isConfirmedOutcome()) count++;
            } catch (RuntimeException failure) {
            }
        return count;
    }
}
