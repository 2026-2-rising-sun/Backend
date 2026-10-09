package com.shoppinglive.commerce.refunds.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundWindowPolicyTest {
    private static final Duration WINDOW = Duration.ofHours(168);

    @Test
    void onlyRequestsStrictlyBeforeThe168HourCutoffAreAccepted() {
        Instant paidAt = Instant.parse("2026-10-01T12:00:00Z");
        Instant cutoff = paidAt.plus(WINDOW);

        assertThat(RefundIntakeService.isRefundWindowOpen(cutoff.minusNanos(1), paidAt)).isTrue();
        assertThat(RefundIntakeService.isRefundWindowOpen(cutoff, paidAt)).isFalse();
        assertThat(RefundIntakeService.isRefundWindowOpen(cutoff.plusNanos(1), paidAt)).isFalse();
    }
}
