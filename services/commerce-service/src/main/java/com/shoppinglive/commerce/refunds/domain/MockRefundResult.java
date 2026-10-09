package com.shoppinglive.commerce.refunds.domain;

import java.time.Instant;

public record MockRefundResult(long refundRequestId, long refundAmount, MockRefundOutcome outcome,
    String refundReference, Instant recordedAt) { }
