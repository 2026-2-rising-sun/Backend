package com.shoppinglive.commerce.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.sales.application.SalesService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private OrderService orderService;

    @Mock
    private OrderJpaRepository orderRepository;

    @Mock
    private PaymentAttemptJpaRepository paymentAttemptRepository;

    @Mock
    private SalesStockJpaRepository salesStockRepository;

    @Mock
    private SalesService salesService;

    @Mock
    private MockPaymentEngine mockPaymentEngine;

    @InjectMocks
    private PaymentService paymentService;

    private Order sampleOrder() {
        Order order = new Order(
            "OD-PAY-1", 10L, 1, 10_000L, "홍길동", "010-1234-5678",
            "hashed", "테스트", null, Instant.now().plusSeconds(900));
        ReflectionTestUtils.setField(order, "id", 1L);
        return order;
    }

    private PaymentAttempt sampleAttempt(PaymentScenario scenario, PaymentStatus status) {
        PaymentAttempt attempt = new PaymentAttempt(1L, scenario, Instant.now());
        ReflectionTestUtils.setField(attempt, "id", 100L);
        ReflectionTestUtils.setField(attempt, "status", status);
        return attempt;
    }

    // ----- startPayment (결제 1) -----

    @Test
    void startPayment_성공하면_저장하고_엔진에_예약() {
        Order order = sampleOrder();
        given(orderService.findByOrderNumberAndPassword("OD-PAY-1", "secret")).willReturn(order);
        given(orderRepository.transitionStatus(order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"))
            .willReturn(1);
        given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
            .willAnswer(inv -> {
                PaymentAttempt a = inv.getArgument(0);
                ReflectionTestUtils.setField(a, "id", 100L);
                return a;
            });

        paymentService.startPayment("OD-PAY-1", "secret", PaymentScenario.INSTANT_SUCCESS);

        verify(mockPaymentEngine).schedule(eq(100L), eq(PaymentScenario.INSTANT_SUCCESS));
    }

    @Test
    void startPayment_전이_실패시_OrderNotEligibleForPaymentException() {
        Order order = sampleOrder();
        given(orderService.findByOrderNumberAndPassword("OD-PAY-1", "secret")).willReturn(order);
        given(orderRepository.transitionStatus(order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"))
            .willReturn(0);

        assertThatThrownBy(() -> paymentService
            .startPayment("OD-PAY-1", "secret", PaymentScenario.INSTANT_SUCCESS))
            .isInstanceOf(OrderNotEligibleForPaymentException.class);
    }

    // ----- resolvePayment (결제 2) -----

    @Test
    void resolvePayment_SUCCESS_시나리오면_PAID_전이_consumeReserved() {
        PaymentAttempt attempt = sampleAttempt(PaymentScenario.INSTANT_SUCCESS, PaymentStatus.PROCESSING);
        Order order = sampleOrder();
        given(paymentAttemptRepository.findById(100L)).willReturn(Optional.of(attempt));
        given(paymentAttemptRepository.resolveIfProcessing(100L, "SUCCESS")).willReturn(1);
        given(orderRepository.transitionStatus(1L, "PAYMENT_CONFIRMING", "PAID")).willReturn(1);
        given(salesStockRepository.consumeReserved(order.getSalesInfoId(), 1)).willReturn(1);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        paymentService.resolvePayment(100L);

        verify(orderRepository).transitionStatus(1L, "PAYMENT_CONFIRMING", "PAID");
        verify(salesStockRepository).consumeReserved(order.getSalesInfoId(), 1);
        verify(salesService, never()).restoreReserved(any(), any(Integer.class));
    }

    @Test
    void resolvePayment_FAILED_시나리오면_FAILED_전이_restoreReserved() {
        PaymentAttempt attempt = sampleAttempt(PaymentScenario.INSTANT_FAIL, PaymentStatus.PROCESSING);
        Order order = sampleOrder();
        given(paymentAttemptRepository.findById(100L)).willReturn(Optional.of(attempt));
        given(paymentAttemptRepository.resolveIfProcessing(100L, "FAILED")).willReturn(1);
        given(orderRepository.transitionStatus(1L, "PAYMENT_CONFIRMING", "FAILED")).willReturn(1);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        paymentService.resolvePayment(100L);

        verify(orderRepository).transitionStatus(1L, "PAYMENT_CONFIRMING", "FAILED");
        verify(salesService).restoreReserved(order.getSalesInfoId(), 1);
        verify(salesStockRepository, never()).consumeReserved(any(), any(Integer.class));
    }

    @Test
    void resolvePayment_이미_terminal이면_no_op() {
        PaymentAttempt attempt = sampleAttempt(PaymentScenario.INSTANT_SUCCESS, PaymentStatus.SUCCESS);
        given(paymentAttemptRepository.findById(100L)).willReturn(Optional.of(attempt));

        paymentService.resolvePayment(100L);

        verify(paymentAttemptRepository, never()).resolveIfProcessing(any(), any());
        verify(orderRepository, never()).transitionStatus(any(), any(), any());
    }

    @Test
    void resolvePayment_조건부_UPDATE_실패시_후속_처리_스킵() {
        PaymentAttempt attempt = sampleAttempt(PaymentScenario.INSTANT_SUCCESS, PaymentStatus.PROCESSING);
        given(paymentAttemptRepository.findById(100L)).willReturn(Optional.of(attempt));
        given(paymentAttemptRepository.resolveIfProcessing(100L, "SUCCESS")).willReturn(0);

        paymentService.resolvePayment(100L);

        verify(orderRepository, never()).transitionStatus(any(), any(), any());
        verify(salesStockRepository, never()).consumeReserved(any(), any(Integer.class));
    }

    @Test
    void resolvePayment_attempt_없으면_no_op() {
        given(paymentAttemptRepository.findById(999L)).willReturn(Optional.empty());

        paymentService.resolvePayment(999L);

        verify(paymentAttemptRepository, never()).resolveIfProcessing(any(), any());
    }
}
