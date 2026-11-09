package com.shoppinglive.payment.service;

import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.dto.*;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class ExecutionResultPublisher {
    public static final String PAYMENTS = "payment.execution-results.v1";
    public static final String REFUNDS = "payment.refund-results.v1";
    private final EventOutbox outbox;

    public ExecutionResultPublisher(EventOutbox outbox) {
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void payment(PaymentExecution result) {
        outbox.append(
                PAYMENTS,
                EventEnvelope.create(
                        "PaymentExecutionResolved",
                        1,
                        "payment-service",
                        result.requestId(),
                        Instant.now(),
                        result));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(RefundExecution result) {
        outbox.append(
                REFUNDS,
                EventEnvelope.create(
                        "RefundExecutionResolved",
                        1,
                        "payment-service",
                        result.requestId(),
                        Instant.now(),
                        result));
    }
}
