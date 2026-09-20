package com.shoppinglive.commerce.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link SalesStatus#canTransitionTo} 도메인 전이 규칙 테스트.
 *
 * <p>P1 명세 판매 4 완료 기준 표에 정의된 전이만 허용해야 한다.
 */
class SalesStatusTransitionRuleTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
        // READY 에서
        "READY, ON_SALE, true",
        "READY, PRIVATE, true",
        "READY, SOLD_OUT, false",
        "READY, READY, false",
        // ON_SALE 에서
        "ON_SALE, PRIVATE, true",
        "ON_SALE, SOLD_OUT, true",
        "ON_SALE, ON_SALE, false",
        "ON_SALE, READY, false",
        // SOLD_OUT 에서
        "SOLD_OUT, ON_SALE, true",
        "SOLD_OUT, PRIVATE, true",
        "SOLD_OUT, SOLD_OUT, false",
        "SOLD_OUT, READY, false",
        // PRIVATE 에서
        "PRIVATE, ON_SALE, true",
        "PRIVATE, SOLD_OUT, true",
        "PRIVATE, PRIVATE, false",
        "PRIVATE, READY, false",
    })
    void canTransitionTo_전이표대로(SalesStatus from, SalesStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0}.isAdminChangeable() = {1}")
    @CsvSource({
        "READY, false",
        "ON_SALE, true",
        "SOLD_OUT, false",
        "PRIVATE, true",
    })
    void isAdminChangeable_ON_SALE와_PRIVATE만(SalesStatus status, boolean adminChangeable) {
        assertThat(status.isAdminChangeable()).isEqualTo(adminChangeable);
    }
}
