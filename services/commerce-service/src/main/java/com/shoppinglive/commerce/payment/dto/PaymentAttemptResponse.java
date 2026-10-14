package com.shoppinglive.commerce.payment.dto;

import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import java.time.Instant;

/** 결제 시도 API 응답 DTO. */
public record PaymentAttemptResponse(
        Long paymentId,
        Long orderId,
        PaymentScenario scenario,
        PaymentStatus status,
        Instant requestedAt,
        Instant resolvedAt,
        Instant scheduledResolveAt) {

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
