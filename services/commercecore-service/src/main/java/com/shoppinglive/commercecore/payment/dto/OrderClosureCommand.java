package com.shoppinglive.commercecore.payment.dto;

import java.time.Instant;

public record OrderClosureCommand(
        String closureRequestId,
        String paymentRequestId,
        Long orderId,
        Long paymentGroupId,
        String referenceNumber,
        long amount,
        String reason,
        Instant closedAt) {}
