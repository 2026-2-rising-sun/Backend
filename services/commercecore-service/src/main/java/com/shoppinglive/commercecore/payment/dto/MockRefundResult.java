package com.shoppinglive.commercecore.payment.dto;

import com.shoppinglive.commercecore.payment.entity.MockRefundOutcome;
import java.time.Instant;

public record MockRefundResult(
        long refundRequestId,
        long refundAmount,
        MockRefundOutcome outcome,
        String refundReference,
        Instant recordedAt) {}
