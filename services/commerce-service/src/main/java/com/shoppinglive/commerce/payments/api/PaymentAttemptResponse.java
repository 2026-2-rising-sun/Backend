package com.shoppinglive.commerce.payments.api;

import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import java.time.Instant;

/**
 * 결제 시도 API 응답 DTO.
 */
public record PaymentAttemptResponse(
    Long paymentId,
    Long orderId,
    PaymentScenario scenario,
    PaymentStatus status,
    Instant requestedAt,
    Instant resolvedAt,
    Instant scheduledResolveAt
) {

    public static PaymentAttemptResponse from(PaymentAttempt attempt) {
        return new PaymentAttemptResponse(
            attempt.getId(),
            attempt.getOrderId(),
            attempt.getScenario(),
            attempt.getStatus(),
            attempt.getRequestedAt(),
            attempt.getResolvedAt(),
            attempt.getScheduledResolveAt());
    }
}
