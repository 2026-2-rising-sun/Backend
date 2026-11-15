package com.shoppinglive.commercecore.payment.service;

import java.time.Duration;
import java.time.Instant;

final class RefundWindowPolicy {
    private static final Duration REFUND_WINDOW = Duration.ofHours(168);

    private RefundWindowPolicy() {}

    static boolean isOpen(Instant now, Instant resolvedAt) {
        return now.isBefore(resolvedAt.plus(REFUND_WINDOW));
    }
}
