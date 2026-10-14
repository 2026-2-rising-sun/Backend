package com.shoppinglive.commerce.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.coupon.service.CouponDiscountAllocator.Item;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponDiscountAllocatorTest {
    @Test
    @DisplayName("큰 할인 금액을 오버플로 없이 배분한다")
    void preservesFullLongDiscountWithoutOverflow() {
        var result =
                CouponDiscountAllocator.allocate(
                        Long.MAX_VALUE,
                        List.of(
                                new Item("a", Long.MAX_VALUE, true),
                                new Item("b", Long.MAX_VALUE, true)));
        assertThat(BigInteger.valueOf(result.get("a")).add(BigInteger.valueOf(result.get("b"))))
                .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));
        assertThat(result.get("a")).isEqualTo(4611686018427387904L);
        assertThat(result.get("b")).isEqualTo(4611686018427387903L);
    }

    @Test
    @DisplayName("다양한 배분에서 합계와 금액 범위를 유지하고 입력 순서에 의존하지 않는다")
    void randomizedAllocationsKeepTotalBoundsAndInputOrderIndependence() {
        Random random = new Random(170);
        for (int sample = 0; sample < 500; sample++) {
            List<Item> items = new ArrayList<>();
            long subtotal = 0;
            for (int index = 0; index < 1 + random.nextInt(20); index++) {
                Item item = new Item("order-" + index, random.nextInt(10000), random.nextBoolean());
                items.add(item);
                if (item.eligible()) {
                    subtotal += item.amount();
                }
            }
            long discount = 1 + random.nextInt(100000);
            var result = CouponDiscountAllocator.allocate(discount, items);
            assertThat(result.values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(Math.min(discount, subtotal));
            for (Item item : items) {
                assertThat(result.get(item.key()))
                        .isBetween(0L, item.eligible() ? item.amount() : 0L);
            }
            Collections.shuffle(items, random);
            assertThat(CouponDiscountAllocator.allocate(discount, items)).isEqualTo(result);
        }
    }

    @Test
    @DisplayName("나머지 금액을 배분하고 할인 대상이 아닌 주문은 제외한다")
    void distributesFractionalWonAndExcludesIneligibleOrders() {
        assertThat(
                        CouponDiscountAllocator.allocate(
                                100,
                                List.of(
                                        new Item("a", 1000, true),
                                        new Item("b", 2000, true),
                                        new Item("c", 5000, false))))
                .isEqualTo(Map.of("a", 33L, "b", 67L, "c", 0L));
    }

    @Test
    @DisplayName("할인은 적용 가능한 금액으로 제한하고 대상 금액이 없는 경우도 처리한다")
    void capsAtEligibleAmountAndHandlesNoEligibleAmount() {
        var items = List.of(new Item("a", 20, true), new Item("b", 100, false));
        assertThat(CouponDiscountAllocator.allocate(100, items))
                .isEqualTo(Map.of("a", 20L, "b", 0L));
        assertThat(CouponDiscountAllocator.allocate(100, List.of(new Item("a", 0, true))))
                .isEqualTo(Map.of("a", 0L));
    }

    @Test
    @DisplayName("나머지가 같으면 일정한 배분 키 순서로 배분한다")
    void breaksEqualRemaindersByStableAllocationKey() {
        assertThat(
                        CouponDiscountAllocator.allocate(
                                2,
                                List.of(
                                        new Item("cart:309", 1000, true),
                                        new Item("cart:205", 1000, true),
                                        new Item("cart:101", 1000, true))))
                .isEqualTo(Map.of("cart:101", 1L, "cart:205", 1L, "cart:309", 0L));
    }

    @Test
    @DisplayName("나머지가 같으면 장바구니 식별자의 숫자 순서로 비교한다")
    void comparesCartItemIdsNumericallyWhenRemaindersTie() {
        assertThat(
                        CouponDiscountAllocator.allocate(
                                1,
                                List.of(
                                        new Item("cart:10", 1000, true),
                                        new Item("cart:2", 1000, true))))
                .isEqualTo(Map.of("cart:2", 1L, "cart:10", 0L));
    }

    @Test
    @DisplayName("중복 주문과 잘못된 금액을 거절한다")
    void rejectsDuplicateOrdersAndInvalidAmounts() {
        assertThatThrownBy(
                        () ->
                                CouponDiscountAllocator.allocate(
                                        1, List.of(new Item("a", 1, true), new Item("a", 2, true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> CouponDiscountAllocator.allocate(0, List.of(new Item("a", 1, true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> CouponDiscountAllocator.allocate(1, List.of(new Item("a", -1, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
