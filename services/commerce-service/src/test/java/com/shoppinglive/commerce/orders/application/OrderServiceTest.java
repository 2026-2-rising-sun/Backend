package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.application.SalesService;
import static org.mockito.Mockito.doThrow;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderJpaRepository orderRepository;

    @Mock
    private SalesService salesService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private OrderService orderService;

    private Order sampleOrder() {
        return new Order(
            "OD-20260920-000001",
            10L,
            2,
            5_000L,
            "홍길동",
            "010-1234-5678",
            "hashed-password",
            "테스트 상품",
            null,
            Instant.parse("2026-09-20T15:00:00Z"));
    }

    // ----- findByOrderNumberAndPassword (주문 3) -----

    @Test
    void findByOrderNumberAndPassword_존재하고_비밀번호_일치시_반환() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("secret", "hashed-password")).willReturn(true);

        Order result = orderService
            .findByOrderNumberAndPassword("OD-20260920-000001", "secret");

        assertThat(result).isSameAs(order);
    }

    @Test
    void findByOrderNumberAndPassword_주문번호_없으면_OrderNotFoundException() {
        given(orderRepository.findByOrderNumber("OD-NOT-EXIST"))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService
            .findByOrderNumberAndPassword("OD-NOT-EXIST", "secret"))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void findByOrderNumberAndPassword_비밀번호_불일치도_OrderNotFoundException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("wrong", "hashed-password")).willReturn(false);

        assertThatThrownBy(() -> orderService
            .findByOrderNumberAndPassword("OD-20260920-000001", "wrong"))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void findByOrderNumberAndPassword_null_비밀번호도_OrderNotFoundException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService
            .findByOrderNumberAndPassword("OD-20260920-000001", null))
            .isInstanceOf(OrderNotFoundException.class);
    }

    // ----- cancelBeforePayment (주문 4) -----

    @Test
    void cancelBeforePayment_성공하면_취소_UPDATE와_재고복구_UPDATE_모두_호출() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("secret", "hashed-password")).willReturn(true);
        given(orderRepository.cancelOrder(order.getId())).willReturn(1);

        orderService.cancelBeforePayment("OD-20260920-000001", "secret");

        verify(orderRepository).cancelOrder(order.getId());
        verify(salesService).restoreReserved(order.getSalesInfoId(), order.getQuantity());
    }

    @Test
    void cancelBeforePayment_비밀번호_불일치시_OrderNotFoundException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("wrong", "hashed-password")).willReturn(false);

        assertThatThrownBy(() -> orderService
            .cancelBeforePayment("OD-20260920-000001", "wrong"))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancelBeforePayment_상태_전이_실패시_OrderCannotBeCancelledException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("secret", "hashed-password")).willReturn(true);
        given(orderRepository.cancelOrder(order.getId())).willReturn(0);

        assertThatThrownBy(() -> orderService
            .cancelBeforePayment("OD-20260920-000001", "secret"))
            .isInstanceOf(OrderCannotBeCancelledException.class);
    }

    @Test
    void cancelBeforePayment_재고_복구_실패시_IllegalStateException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("secret", "hashed-password")).willReturn(true);
        given(orderRepository.cancelOrder(order.getId())).willReturn(1);
        doThrow(new IllegalStateException("stock restoration failed"))
            .when(salesService).restoreReserved(order.getSalesInfoId(), order.getQuantity());

        assertThatThrownBy(() -> orderService
            .cancelBeforePayment("OD-20260920-000001", "secret"))
            .isInstanceOf(IllegalStateException.class);
    }
}
