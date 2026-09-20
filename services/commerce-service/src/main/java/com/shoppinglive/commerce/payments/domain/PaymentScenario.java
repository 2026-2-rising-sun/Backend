package com.shoppinglive.commerce.payments.domain;

import java.time.Duration;

/**
 * Mock 결제 시나리오. P1 명세 결제 4 완료 기준의 4 시나리오.
 *
 * <p>{@link #delay} 는 결제 시작 후 확정까지 대기 시간, {@link #outcome} 은 확정될 최종 상태.
 * 결제 2 의 Mock 엔진이 이 두 필드를 활용해 결정적 재현을 구현한다.
 */
public enum PaymentScenario {
    INSTANT_SUCCESS(Duration.ZERO, PaymentStatus.SUCCESS),
    INSTANT_FAIL(Duration.ZERO, PaymentStatus.FAILED),
    DELAYED_SUCCESS(Duration.ofMillis(200), PaymentStatus.SUCCESS),
    DELAYED_FAIL(Duration.ofMillis(200), PaymentStatus.FAILED);

    private final Duration delay;
    private final PaymentStatus outcome;

    PaymentScenario(Duration delay, PaymentStatus outcome) {
        this.delay = delay;
        this.outcome = outcome;
    }

    public Duration getDelay() {
        return delay;
    }

    public PaymentStatus getOutcome() {
        return outcome;
    }

    public boolean isDelayed() {
        return !delay.isZero();
    }
}
