package com.shoppinglive.commerce.coupons.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CouponDefinitionTest {
    private final Instant start = Instant.parse("2026-10-09T00:00:00Z");
    private final Instant end = start.plus(Duration.ofHours(720));

    @Test
    void permitsExactQuantityAndDurationMaximumWithEqualExpiration() {
        assertThatCode(() -> CouponDefinition.validate("쿠폰", Long.MAX_VALUE, 10000, start, end, end, List.of(1L)))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsExcessQuantityDurationAndExpirationBeforeEventEnd() {
        assertThatThrownBy(() -> CouponDefinition.validate("쿠폰", 1, 10001, start, end, end, List.of(1L)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CouponDefinition.validate("쿠폰", 1, 1, start, end.plusNanos(1), end.plusNanos(1), List.of(1L)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CouponDefinition.validate("쿠폰", 1, 1, start, end, end.minusNanos(1), List.of(1L)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsDuplicateTargetsAndNonpositiveDiscount() {
        assertThatThrownBy(() -> CouponDefinition.validate("쿠폰", 1, 1, start, end, end, List.of(1L, 1L)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CouponDefinition.validate("쿠폰", 0, 1, start, end, end, List.of(1L)))
            .isInstanceOf(BusinessException.class);
    }
}
