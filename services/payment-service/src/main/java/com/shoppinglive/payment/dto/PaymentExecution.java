package com.shoppinglive.payment.dto;

import com.shoppinglive.payment.entity.*;
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
    public static PaymentExecution from(PaymentAttempt p) {
        return new PaymentExecution(
                p.getRequestId(),
                p.getPaymentId(),
                p.getOrderId(),
                p.getPaymentGroupId(),
                p.getReferenceNumber(),
                p.getAmount(),
                p.getScenario(),
                p.getStatus(),
                p.getRequestedAt(),
                p.getResolvedAt(),
                p.getScheduledResolveAt());
    }
}
