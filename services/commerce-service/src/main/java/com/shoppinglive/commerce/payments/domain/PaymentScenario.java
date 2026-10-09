package com.shoppinglive.commerce.payments.domain;

import java.time.Duration;

/**
 * 서버의 local/test 설정으로만 선택하는 Mock 결제 시나리오.
 *
 * <p>{@link #delay} 는 결제 시작 후 확정까지 대기 시간, {@link #outcome} 은 확정될 최종 상태.
 * 결제 2 의 Mock 엔진이 이 두 필드를 활용해 결정적 재현을 구현한다.
 */
public enum PaymentScenario {
    INSTANT_SUCCESS(Duration.ZERO, PaymentStatus.SUCCESS),
    INSTANT_FAIL(Duration.ZERO, PaymentStatus.FAILED),
    DELAYED_SUCCESS(Duration.ofMillis(200), PaymentStatus.SUCCESS),
    DELAYED_FAIL(Duration.ofMillis(200), PaymentStatus.FAILED),
    SUCCESS_RESPONSE_LOST(Duration.ZERO, PaymentStatus.SUCCESS),
    FAILURE_RESPONSE_LOST(Duration.ZERO, PaymentStatus.FAILED),
    UNAVAILABLE_BEFORE_RESULT(Duration.ZERO, PaymentStatus.SUCCESS);

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

    public boolean losesFirstResponse() {
        return this == SUCCESS_RESPONSE_LOST || this == FAILURE_RESPONSE_LOST;
    }

    public boolean unavailableBeforeResult() {
        return this == UNAVAILABLE_BEFORE_RESULT;
    }
}
