package com.shoppinglive.commercecore.payment.dto;

import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import java.time.Instant;

public record RefundExecution(
        String requestId,
        Long executionId,
        Long coreRefundRequestId,
        Long paymentId,
        Long orderId,
        Long paymentGroupId,
        String referenceNumber,
        long amount,
        RefundStatus status,
        Instant requestedAt,
        Instant resolvedAt,
        int retryCount,
        Instant nextActionAt) {}
