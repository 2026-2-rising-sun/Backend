package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundRetryPolicyTest {
    private final RefundRetryPolicy policy = new RefundRetryPolicy();

    @Test
    @DisplayName("결과 미확정 재시도 지연은 1초와 2초 및 4초 범위를 지킨다")
    void unknownDelaysStayWithinTheOneTwoAndFourSecondJitterCaps() {
        for (int used = 0; used < 3; used++) {
            for (int sample = 0; sample < 100; sample++) {
                assertThat(policy.afterUnknown(used))
                        .isBetween(Duration.ZERO, Duration.ofSeconds(1L << used));
            }
        }
    }

    @Test
    @DisplayName("재시도 소진 후 1분 뒤 결과 조회를 예약한다")
    void exhaustionSchedulesResultLookupAfterOneMinute() {
        assertThat(policy.afterUnknown(3)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("잘못된 호출 횟수로 다른 실행 시간을 선택할 수 없다")
    void invalidInvocationCountsCannotSelectAnotherExecutionWindow() {
        assertThatThrownBy(() -> policy.afterUnknown(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.afterUnknown(4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
