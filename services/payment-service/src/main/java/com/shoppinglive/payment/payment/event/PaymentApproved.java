package com.shoppinglive.payment.payment.event;

import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;

/** 결제 승인 확정 이벤트 payload. 계약 원본은 {@code contracts/events/payment-execution-results.yaml}. */
public record PaymentApproved(long paymentId, String orderRef, long amount, Instant approvedAt) {
    public static final String TOPIC = "payment.execution-results.v1";
    public static final String EVENT_TYPE = "PaymentApproved";

    /** Kafka 키(aggregateId)는 orderRef다. */
    public EventEnvelope<PaymentApproved> envelope(Instant occurredAt) {
        return EventEnvelope.create(EVENT_TYPE, 1, "payment-service", orderRef, occurredAt, this);
    }
}
