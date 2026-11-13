package com.shoppinglive.commercecore.order.entity;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.common.core.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderRefundAmountsTest {
    @Test
    @DisplayName("4천원 세 개에서 쿠폰 2천원을 쓰면 순차 환불은 4천·4천·2천원이다")
    void refundsGrossPriceFirstWithinRemainingPaidAmount() {
        long remaining = 10000;
        long first = OrderRefundAmounts.refund(4000, 1, remaining);
        remaining -= first;
        long second = OrderRefundAmounts.refund(4000, 1, remaining);
        remaining -= second;
        long third = OrderRefundAmounts.refund(4000, 1, remaining);
        assertThat(new long[] {first, second, third}).containsExactly(4000, 4000, 2000);
    }

    @Test
    @DisplayName("두 개를 먼저 환불해도 누적 8천원과 마지막 2천원으로 제한한다")
    void batchRefundMatchesGrossPriceCap() {
        assertThat(OrderRefundAmounts.refund(4000, 2, 10000)).isEqualTo(8000);
        assertThat(OrderRefundAmounts.refund(4000, 1, 2000)).isEqualTo(2000);
        assertThat(OrderRefundAmounts.refund(4000, 3, 10000)).isEqualTo(10000);
    }

    @Test
    @DisplayName("실결제액을 이미 전부 반환한 잔여 수량의 환불 금액은 0원이다")
    void remainingQuantityCanHaveZeroRefundAmount() {
        assertThat(OrderRefundAmounts.refund(4000, 1, 0)).isZero();
    }

    @Test
    @DisplayName("음수 잔액·0 수량·금액 곱셈 초과를 허용하지 않는다")
    void invalidInputsCannotExceedMoneyBounds() {
        assertThatThrownBy(() -> OrderRefundAmounts.refund(4000, 1, -1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrderRefundAmounts.refund(4000, 0, 10000))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OrderRefundAmounts.refund(Long.MAX_VALUE, 2, Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class);
    }
}
