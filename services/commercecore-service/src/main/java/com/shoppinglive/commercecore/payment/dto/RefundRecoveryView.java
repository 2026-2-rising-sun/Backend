package com.shoppinglive.commercecore.payment.dto;

import java.time.Instant;

public record RefundRecoveryView(int retryCount, boolean retryExhausted, Instant nextActionAt) {}
