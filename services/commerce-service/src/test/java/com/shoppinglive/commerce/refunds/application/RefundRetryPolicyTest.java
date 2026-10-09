package com.shoppinglive.commerce.refunds.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RefundRetryPolicyTest {
    private final RefundRetryPolicy policy = new RefundRetryPolicy();

    @Test
    void unknownDelaysStayWithinTheOneTwoAndFourSecondJitterCaps() {
        for (int used = 0; used < 3; used++) {
            for (int sample = 0; sample < 100; sample++) {
                assertThat(policy.afterUnknown(used)).isBetween(Duration.ZERO, Duration.ofSeconds(1L << used));
            }
        }
    }

    @Test
    void exhaustionSchedulesResultLookupAfterOneMinute() {
        assertThat(policy.afterUnknown(3)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void invalidInvocationCountsCannotSelectAnotherExecutionWindow() {
        assertThatThrownBy(() -> policy.afterUnknown(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.afterUnknown(4)).isInstanceOf(IllegalArgumentException.class);
    }
}
