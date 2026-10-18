package com.shoppinglive.commerce.sales.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link SalesStatus#canTransitionTo} 도메인 전이 규칙 테스트.
 *
 * <p>P1 명세 판매 4 완료 기준 표에 정의된 전이만 허용해야 한다.
 */
class SalesStatusTransitionRuleTest {

    @DisplayName("판매 상태는 전이표에 정의된 전이만 허용한다")
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
    void allowsOnlyTransitionsDefinedInStatusTable(
            SalesStatus from, SalesStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @DisplayName("판매자는 ON_SALE과 PRIVATE만 직접 지정할 수 있다")
    @ParameterizedTest(name = "{0}.isSellerChangeable() = {1}")
    @CsvSource({
        "READY, false",
        "ON_SALE, true",
        "SOLD_OUT, false",
        "PRIVATE, true",
    })
    void allowsOnlyOnSaleAndPrivateAsSellerTargets(SalesStatus status, boolean sellerChangeable) {
        assertThat(status.isSellerChangeable()).isEqualTo(sellerChangeable);
    }
}
