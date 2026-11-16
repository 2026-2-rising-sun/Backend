package com.shoppinglive.commercecore.payment.dto;

import com.shoppinglive.commercecore.payment.entity.*;
import java.time.Instant;

public record PaymentExecution(
        String requestId,
        Long paymentId,
        Long orderId,
        Long paymentGroupId,
        String referenceNumber,
        long amount,
        PaymentScenario scenario,
        PaymentStatus status,
        Instant requestedAt,
        Instant resolvedAt,
        Instant scheduledResolveAt) {
    public Long getId() {
        return paymentId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getPaymentGroupId() {
        return paymentGroupId;
    }

    public com.shoppinglive.commercecore.payment.entity.PaymentScenario getScenario() {
        return scenario;
    }

    public com.shoppinglive.commercecore.payment.entity.PaymentStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getScheduledResolveAt() {
        return scheduledResolveAt;
    }
}
