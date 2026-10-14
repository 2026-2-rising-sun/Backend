package com.shoppinglive.commerce.coupon.entity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponDefinitionTest {
    private final Instant start = Instant.parse("2026-10-09T00:00:00Z");
    private final Instant end = start.plus(Duration.ofHours(720));

    @Test
    @DisplayName("최대 발급 수량과 기간 및 행사 종료와 같은 만료 시각을 허용한다")
    void permitsExactQuantityAndDurationMaximumWithEqualExpiration() {
        assertThatCode(
                        () ->
                                CouponDefinition.validate(
                                        "쿠폰", Long.MAX_VALUE, 10000, start, end, end, List.of(1L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("초과 수량과 기간 및 행사 종료 전 만료를 거절한다")
    void rejectsExcessQuantityDurationAndExpirationBeforeEventEnd() {
        assertThatThrownBy(
                        () ->
                                CouponDefinition.validate(
                                        "쿠폰", 1, 10001, start, end, end, List.of(1L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                CouponDefinition.validate(
                                        "쿠폰",
                                        1,
                                        1,
                                        start,
                                        end.plusNanos(1),
                                        end.plusNanos(1),
                                        List.of(1L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                CouponDefinition.validate(
                                        "쿠폰", 1, 1, start, end, end.minusNanos(1), List.of(1L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("중복 적용 대상과 0 이하 할인 금액을 거절한다")
    void rejectsDuplicateTargetsAndNonpositiveDiscount() {
        assertThatThrownBy(
                        () ->
                                CouponDefinition.validate(
                                        "쿠폰", 1, 1, start, end, end, List.of(1L, 1L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () -> CouponDefinition.validate("쿠폰", 0, 1, start, end, end, List.of(1L)))
                .isInstanceOf(BusinessException.class);
    }
}
