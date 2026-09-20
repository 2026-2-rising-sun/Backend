package com.shoppinglive.commerce.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import java.time.Instant;
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

    @InjectMocks
    private PaymentService paymentService;

    private Order sampleOrder() {
        Order order = new Order(
            "OD-PAY-1",
            10L,
            1,
            10_000L,
            "홍길동",
            "010-1234-5678",
            "hashed",
            "테스트",
            null,
            Instant.now().plusSeconds(900));
        // JPA 없이 unit test 라 id 를 수동 세팅
        ReflectionTestUtils.setField(order, "id", 1L);
        return order;
    }

    @Test
    void startPayment_성공하면_PaymentAttempt_저장하고_반환() {
        Order order = sampleOrder();
        given(orderService.findByOrderNumberAndPassword("OD-PAY-1", "secret")).willReturn(order);
        given(orderRepository.transitionStatus(order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"))
            .willReturn(1);
        given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
            .willAnswer(inv -> inv.getArgument(0));

        PaymentAttempt result = paymentService.startPayment(
            "OD-PAY-1", "secret", PaymentScenario.INSTANT_SUCCESS);

        assertThat(result.getScenario()).isEqualTo(PaymentScenario.INSTANT_SUCCESS);
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(result.getScheduledResolveAt()).isEqualTo(result.getRequestedAt());
    }

    @Test
    void startPayment_DELAYED_시나리오면_scheduledResolveAt_에_delay_반영() {
        Order order = sampleOrder();
        given(orderService.findByOrderNumberAndPassword("OD-PAY-1", "secret")).willReturn(order);
        given(orderRepository.transitionStatus(order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"))
            .willReturn(1);
        given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
            .willAnswer(inv -> inv.getArgument(0));

        PaymentAttempt result = paymentService.startPayment(
            "OD-PAY-1", "secret", PaymentScenario.DELAYED_SUCCESS);

        assertThat(result.getScheduledResolveAt()).isAfter(result.getRequestedAt());
    }

    @Test
    void startPayment_scenario_null이면_INSTANT_SUCCESS_default() {
        Order order = sampleOrder();
        given(orderService.findByOrderNumberAndPassword("OD-PAY-1", "secret")).willReturn(order);
        given(orderRepository.transitionStatus(order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"))
            .willReturn(1);
        given(paymentAttemptRepository.save(any(PaymentAttempt.class)))
            .willAnswer(inv -> inv.getArgument(0));

        PaymentAttempt result = paymentService.startPayment("OD-PAY-1", "secret", null);

        assertThat(result.getScenario()).isEqualTo(PaymentScenario.INSTANT_SUCCESS);
    }

    @Test
    void startPayment_주문이_PENDING_PAYMENT_아니면_OrderNotEligibleForPaymentException() {
        Order order = sampleOrder();
        given(orderService.findByOrderNumberAndPassword("OD-PAY-1", "secret")).willReturn(order);
        given(orderRepository.transitionStatus(order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"))
            .willReturn(0);

        assertThatThrownBy(() -> paymentService.startPayment(
            "OD-PAY-1", "secret", PaymentScenario.INSTANT_SUCCESS))
            .isInstanceOf(OrderNotEligibleForPaymentException.class);
    }
}
