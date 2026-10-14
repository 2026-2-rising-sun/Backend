package com.shoppinglive.commerce.payment.dto;

import com.shoppinglive.commerce.payment.entity.MockRefundOutcome;
import java.time.Instant;

public record MockRefundResult(
        long refundRequestId,
        long refundAmount,
        MockRefundOutcome outcome,
        String refundReference,
        Instant recordedAt) {}
