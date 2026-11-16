package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.payment.service.*;

/** 실제 Payment DB 실행 후 HTTP 결과를 Core에 반영하는 회귀용 드라이버. */
public class RefundBoundaryTestDriver {
    private final PaymentDispatcher dispatcher;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final PaymentDeliveryStore deliveries;
    private final PaymentClient client;
    private final PaymentResultHandler handler;
    private final com.shoppinglive.payment.service.RefundExecutionService execution;

    public RefundBoundaryTestDriver(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            PaymentDispatcher dispatcher,
            PaymentDeliveryStore deliveries,
            PaymentClient client,
            PaymentResultHandler handler,
            com.shoppinglive.payment.service.RefundExecutionService execution) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.deliveries = deliveries;
        this.client = client;
        this.handler = handler;
        this.execution = execution;
    }

    public boolean execute(long id) {
        return execute(id, MockRefundScenario.SUCCESS);
    }

    public boolean execute(long id, MockRefundScenario scenario) {
        boolean submitted = submit(id);
        try {
            execution.execute(id, scenario);
        } catch (RuntimeException failure) {
            handler.refund(client.refund(id));
            throw failure;
        }
        return handler.refund(client.refund(id)) || submitted;
    }

    /** baseline의 명시적 실행 호출처럼 자동 실행 flag와 별개로 테스트가 원문을 직접 제출한다. */
    public boolean submit(long id) {
        var d = deliveries.byRefund(id);
        return handler.refund(
                client.refund(
                        deliveries.read(
                                d.command(),
                                com.shoppinglive.commercecore.payment.dto.RefundCommand.class)));
    }

    public int recoverPending() {
        int applied = 0;
        for (Long id :
                jdbc.queryForList(
                        "SELECT core_refund_request_id FROM payment_delivery_request WHERE kind='REFUND' AND status IN ('PROCESSING','UNKNOWN')",
                        Long.class))
            try {
                if (dispatcher.refund(id)) applied++;
            } catch (RuntimeException failure) {
            }
        return applied;
    }

    public boolean executeClaimed(RefundRecoveryStore.Lease lease, MockRefundScenario scenario) {
        execution.executeClaimed(lease, scenario);
        // 작업자 처리 수는 Payment 확정 수와 Core 반영 수를 중복 합산하지 않는다.
        return handler.refund(client.refund(lease.requestId()));
    }
}
