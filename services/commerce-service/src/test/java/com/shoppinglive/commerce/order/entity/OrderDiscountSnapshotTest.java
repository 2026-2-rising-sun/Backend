package com.shoppinglive.commerce.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderDiscountSnapshotTest {
    @Test
    @DisplayName("기존 생성자는 원래 주문 금액을 유지하고 할인 금액을 0으로 설정한다")
    void legacyConstructorKeepsGrossAmountAndInitializesDiscountToZero() {
        var order =
                new Order(
                        "order-1",
                        1L,
                        2,
                        1000,
                        "buyer",
                        "01012345678",
                        "11111111-1111-4111-8111-111111111111",
                        "product",
                        null,
                        Instant.now());

        assertThat(order.getTotalAmount()).isEqualTo(2000);
        assertThat(order.getDiscountAmount()).isZero();
        assertThat(order.getPayableAmount()).isEqualTo(2000);
    }

    @Test
    @DisplayName("할인 금액과 결제 금액은 원래 주문 금액 범위 안에 있어야 한다")
    void discountAndPayableAmountsAreBoundedByOriginalOrderAmount() {
        var order =
                new Order(
                        "order-2",
                        1L,
                        2,
                        1000,
                        "buyer",
                        "01012345678",
                        "11111111-1111-4111-8111-111111111111",
                        "product",
                        null,
                        Instant.now(),
                        null,
                        null,
                        1500);
        assertThat(order.getTotalAmount()).isEqualTo(2000);
        assertThat(order.getDiscountAmount()).isEqualTo(1500);
        assertThat(order.getPayableAmount()).isEqualTo(500);

        assertThatThrownBy(
                        () ->
                                new Order(
                                        "order-3",
                                        1L,
                                        1,
                                        1000,
                                        "buyer",
                                        "01012345678",
                                        "11111111-1111-4111-8111-111111111111",
                                        "product",
                                        null,
                                        Instant.now(),
                                        null,
                                        null,
                                        1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 그룹은 주문 합계와 일치하는 금액 스냅샷을 저장한다")
    void paymentGroupStoresMatchingAggregateSnapshot() {
        var group =
                new PaymentGroup(
                        "group",
                        "11111111-1111-4111-8111-111111111111",
                        "key",
                        "fingerprint",
                        2000,
                        Instant.now(),
                        1500);

        assertThat(group.getTotalAmount()).isEqualTo(2000);
        assertThat(group.getDiscountAmount()).isEqualTo(1500);
        assertThat(group.getPayableAmount()).isEqualTo(500);
    }
}
