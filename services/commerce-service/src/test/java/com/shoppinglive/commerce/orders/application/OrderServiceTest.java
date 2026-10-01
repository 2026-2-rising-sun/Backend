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

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderJpaRepository orderRepository;

    @Mock
    private SalesService salesService;

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
            "11111111-1111-4111-8111-111111111111",
            "테스트 상품",
            null,
            Instant.parse("2026-09-20T15:00:00Z"));
    }

    // ----- findByOrderNumberAndMemberId (주문 3) -----

    @Test
    void findByOrderNumberAndMemberId_존재하고_회원_일치시_반환() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
            .willReturn(Optional.of(order));

        Order result = orderService
            .findByOrderNumberAndMemberId("OD-20260920-000001", "11111111-1111-4111-8111-111111111111");

        assertThat(result).isSameAs(order);
    }

    @Test
    void findByOrderNumberAndMemberId_주문번호_없으면_OrderNotFoundException() {
        given(orderRepository.findByOrderNumberAndMemberId("OD-NOT-EXIST", "11111111-1111-4111-8111-111111111111"))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService
            .findByOrderNumberAndMemberId("OD-NOT-EXIST", "11111111-1111-4111-8111-111111111111"))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void findByOrderNumberAndMemberId_타인_주문도_OrderNotFoundException() {
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", "22222222-2222-4222-8222-222222222222"))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService
            .findByOrderNumberAndMemberId("OD-20260920-000001", "22222222-2222-4222-8222-222222222222"))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void findByOrderNumberAndMemberId_null_회원도_OrderNotFoundException() {
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", null))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService
            .findByOrderNumberAndMemberId("OD-20260920-000001", null))
            .isInstanceOf(OrderNotFoundException.class);
    }

    // ----- cancelBeforePayment (주문 4) -----

    @Test
    void cancelBeforePayment_성공하면_취소_UPDATE와_재고복구_UPDATE_모두_호출() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
            .willReturn(Optional.of(order));
        given(orderRepository.cancelOrder(order.getId())).willReturn(1);

        orderService.cancelBeforePayment("OD-20260920-000001", "11111111-1111-4111-8111-111111111111");

        verify(orderRepository).cancelOrder(order.getId());
        verify(salesService).restoreReserved(order.getSalesInfoId(), order.getQuantity());
    }

    @Test
    void cancelBeforePayment_타인_주문이면_OrderNotFoundException() {
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", "22222222-2222-4222-8222-222222222222"))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService
            .cancelBeforePayment("OD-20260920-000001", "22222222-2222-4222-8222-222222222222"))
            .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancelBeforePayment_상태_전이_실패시_OrderCannotBeCancelledException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
            .willReturn(Optional.of(order));
        given(orderRepository.cancelOrder(order.getId())).willReturn(0);

        assertThatThrownBy(() -> orderService
            .cancelBeforePayment("OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
            .isInstanceOf(OrderCannotBeCancelledException.class);
    }

    @Test
    void cancelBeforePayment_재고_복구_실패시_IllegalStateException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
            .willReturn(Optional.of(order));
        given(orderRepository.cancelOrder(order.getId())).willReturn(1);
        doThrow(new IllegalStateException("stock restoration failed"))
            .when(salesService).restoreReserved(order.getSalesInfoId(), order.getQuantity());

        assertThatThrownBy(() -> orderService
            .cancelBeforePayment("OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
            .isInstanceOf(IllegalStateException.class);
    }
}
