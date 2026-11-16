package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.payment.service.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;

@ExtendWith(MockitoExtension.class)
class OrderPaymentOutcomeServiceTest {
    @Mock OrderTimeSource time;
    @Mock OrderSheetLifecycleService lifecycle;
    @Mock LateApprovalRefundStore lateRefunds;
    @Mock PaymentGroupRepository groups;
    @Mock OrderJpaRepository orders;
    @Mock CartItemRepository cart;
    @Mock SkuService skus;
    @Mock PaymentDeliveryStore payments;
    @Mock PurchaseGuard guard;
    @Mock TransactionTemplate tx;
    @Mock JdbcTemplate jdbc;
    @Mock CouponReservationService coupons;
    @Mock PaymentGroupEventPublisher events;
    @Mock EntityManager entityManager;
    @Mock PaymentGroup group;
    OrderPaymentOutcomeService outcomes;

    @BeforeEach
    void setUp() {
        outcomes =
                new OrderPaymentOutcomeService(
                        time,
                        lifecycle,
                        lateRefunds,
                        groups,
                        orders,
                        cart,
                        skus,
                        payments,
                        guard,
                        tx,
                        jdbc,
                        coupons,
                        events);
        ReflectionTestUtils.setField(outcomes, "entityManager", entityManager);
        when(tx.execute(any()))
                .thenAnswer(
                        invocation ->
                                invocation
                                        .<TransactionCallback<?>>getArgument(0)
                                        .doInTransaction(new SimpleTransactionStatus()));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"SUCCESS", "FAILED"})
    @DisplayName("주문 저장 순서가 반대여도 결제 성공 소진과 실패 해제는 SKU 오름차순으로 처리한다")
    void appliesInventoryInSkuOrderRegardlessOfOrderIds(PaymentStatus status) {
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        when(groups.findById(1L)).thenReturn(Optional.of(group));
        when(groups.lockById(1L)).thenReturn(Optional.of(group));
        when(group.getId()).thenReturn(1L);
        when(group.getMemberId()).thenReturn("구매자");
        when(group.getPaymentId()).thenReturn(99L);
        when(group.getStatus()).thenReturn(OrderStatus.PAYMENT_CONFIRMING);
        when(group.getPayableAmount()).thenReturn(3000L);
        when(time.now()).thenReturn(now);
        when(payments.get("result"))
                .thenReturn(
                        new PaymentDeliveryStore.Delivery(
                                7L, "result", "PAYMENT", "{}", null, "PROCESSING"));
        Order highSku = item(10L, 200L, 2);
        Order lowSku = item(20L, 100L, 1);
        when(orders.findByPaymentGroupIdOrderByIdAsc(1L)).thenReturn(List.of(highSku, lowSku));
        when(orders.transitionStatus(any(), any(), any())).thenReturn(1);
        var result =
                new PaymentExecution(
                        "result", 99L, null, 1L, "묶음", 3000, null, status, now, now, now);

        assertThat(outcomes.apply(result)).isTrue();

        InOrder inventory = inOrder(skus);
        if (status == PaymentStatus.SUCCESS) {
            inventory.verify(skus).consume(100L, 1);
            inventory.verify(skus).consume(200L, 2);
        } else {
            inventory.verify(skus).release(100L, 1);
            inventory.verify(skus).release(200L, 2);
        }
        inventory.verifyNoMoreInteractions();
        verify(group)
                .transition(
                        status == PaymentStatus.SUCCESS ? OrderStatus.PAID : OrderStatus.FAILED);
        verify(payments).record(any(), eq(result), eq(status.name()));
    }

    private Order item(long orderId, long skuId, int quantity) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getTradeSkuId()).thenReturn(skuId);
        when(order.getQuantity()).thenReturn(quantity);
        return order;
    }
}
