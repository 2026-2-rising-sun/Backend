package com.shoppinglive.commerce.coupons.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.coupons.domain.CouponDiscountAllocator.Item;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CouponDiscountAllocatorTest {
    @Test
    void distributesFractionalWonAndExcludesIneligibleOrders() {
        assertThat(CouponDiscountAllocator.allocate(100, List.of(
            new Item("a", 1000, true), new Item("b", 2000, true), new Item("c", 5000, false))))
            .isEqualTo(Map.of("a", 33L, "b", 67L, "c", 0L));
    }

    @Test
    void capsAtEligibleAmountAndHandlesNoEligibleAmount() {
        var items = List.of(new Item("a", 20, true), new Item("b", 100, false));
        assertThat(CouponDiscountAllocator.allocate(100, items)).isEqualTo(Map.of("a", 20L, "b", 0L));
        assertThat(CouponDiscountAllocator.allocate(100, List.of(new Item("a", 0, true))))
            .isEqualTo(Map.of("a", 0L));
    }

    @Test
    void breaksEqualRemaindersByImmutableOrderNumber() {
        assertThat(CouponDiscountAllocator.allocate(1, List.of(new Item("b", 10, true), new Item("a", 10, true))))
            .isEqualTo(Map.of("a", 1L, "b", 0L));
    }

    @Test
    void rejectsDuplicateOrdersAndInvalidAmounts() {
        assertThatThrownBy(() -> CouponDiscountAllocator.allocate(1,
            List.of(new Item("a", 1, true), new Item("a", 2, true)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CouponDiscountAllocator.allocate(0, List.of(new Item("a", 1, true))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CouponDiscountAllocator.allocate(1, List.of(new Item("a", -1, true))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
