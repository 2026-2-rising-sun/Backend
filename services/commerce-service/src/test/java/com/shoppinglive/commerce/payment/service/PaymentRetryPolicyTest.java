package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRetryPolicyTest {
    @Test
    @DisplayName("재시도 지연 범위와 결과 조회 대기 시간이 정책과 일치한다")
    void jitterCapsAndQueryOnlyDelayMatchConfirmedPolicy() {
        var policy = new PaymentRetryPolicy();
        for (int count = 0; count < 3; count++) {
            for (int i = 0; i < 100; i++) {
                assertThat(policy.afterUnknown(count))
                        .isBetween(Duration.ZERO, Duration.ofSeconds(1L << count));
            }
        }
        assertThat(policy.afterUnknown(3)).isEqualTo(Duration.ofMinutes(1));
        assertThatThrownBy(() -> policy.afterUnknown(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.afterUnknown(4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
