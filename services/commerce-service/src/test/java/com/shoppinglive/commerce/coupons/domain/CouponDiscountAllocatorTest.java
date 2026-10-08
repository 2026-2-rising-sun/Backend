package com.shoppinglive.commerce.coupons.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.coupons.domain.CouponDiscountAllocator.Item;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CouponDiscountAllocatorTest {
    @Test
    void preservesFullLongDiscountWithoutOverflow() {
        var result = CouponDiscountAllocator.allocate(Long.MAX_VALUE, List.of(
            new Item("a", Long.MAX_VALUE, true), new Item("b", Long.MAX_VALUE, true)));
        assertThat(BigInteger.valueOf(result.get("a")).add(BigInteger.valueOf(result.get("b"))))
            .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));
        assertThat(result.get("a")).isEqualTo(4611686018427387904L);
        assertThat(result.get("b")).isEqualTo(4611686018427387903L);
    }

    @Test
    void randomizedAllocationsKeepTotalBoundsAndInputOrderIndependence() {
        Random random = new Random(170);
        for (int sample = 0; sample < 500; sample++) {
            List<Item> items = new ArrayList<>();
            long subtotal = 0;
            for (int index = 0; index < 1 + random.nextInt(20); index++) {
                Item item = new Item("order-" + index, random.nextInt(10000), random.nextBoolean());
                items.add(item);
                if (item.eligible()) subtotal += item.amount();
            }
            long discount = 1 + random.nextInt(100000);
            var result = CouponDiscountAllocator.allocate(discount, items);
            assertThat(result.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(Math.min(discount, subtotal));
            for (Item item : items) {
                assertThat(result.get(item.orderNumber())).isBetween(0L, item.eligible() ? item.amount() : 0L);
            }
            Collections.shuffle(items, random);
            assertThat(CouponDiscountAllocator.allocate(discount, items)).isEqualTo(result);
        }
    }
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
