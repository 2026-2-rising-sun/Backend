package com.shoppinglive.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.exception.OrderCannotBeCancelledException;
import com.shoppinglive.commerce.order.exception.OrderNotFoundException;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.sales.service.SalesService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderJpaRepository orderRepository;

    @Mock private SalesService salesService;

    @InjectMocks private OrderService orderService;

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
    @DisplayName("주문번호가 존재하고 회원이 일치하면 주문을 반환한다")
    void returnsOrderForMatchingNumberAndMember() {
        Order order = sampleOrder();
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
                .willReturn(Optional.of(order));

        Order result =
                orderService.findByOrderNumberAndMemberId(
                        "OD-20260920-000001", "11111111-1111-4111-8111-111111111111");

        assertThat(result).isSameAs(order);
    }

    @Test
    @DisplayName("주문번호가 없으면 OrderNotFoundException을 던진다")
    void rejectsMissingOrderNumberWithOrderNotFound() {
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-NOT-EXIST", "11111111-1111-4111-8111-111111111111"))
                .willReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                orderService.findByOrderNumberAndMemberId(
                                        "OD-NOT-EXIST", "11111111-1111-4111-8111-111111111111"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("타인 주문은 OrderNotFoundException으로 숨긴다")
    void hidesAnotherMembersOrderWithOrderNotFound() {
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-20260920-000001", "22222222-2222-4222-8222-222222222222"))
                .willReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                orderService.findByOrderNumberAndMemberId(
                                        "OD-20260920-000001",
                                        "22222222-2222-4222-8222-222222222222"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("회원이 null이면 OrderNotFoundException을 던진다")
    void rejectsNullMemberWithOrderNotFound() {
        given(orderRepository.findByOrderNumberAndMemberId("OD-20260920-000001", null))
                .willReturn(Optional.empty());

        assertThatThrownBy(
                        () -> orderService.findByOrderNumberAndMemberId("OD-20260920-000001", null))
                .isInstanceOf(OrderNotFoundException.class);
    }

    // ----- cancelBeforePayment (주문 4) -----

    @Test
    @DisplayName("결제 전 취소가 성공하면 주문 취소와 예약 재고 복구를 수행한다")
    void cancelsPendingOrderAndRestoresReservedStock() {
        Order order = sampleOrder();
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
                .willReturn(Optional.of(order));
        given(orderRepository.cancelOrder(order.getId())).willReturn(1);

        orderService.cancelBeforePayment(
                "OD-20260920-000001", "11111111-1111-4111-8111-111111111111");

        verify(orderRepository).cancelOrder(order.getId());
        verify(salesService).restoreReserved(order.getSalesInfoId(), order.getQuantity());
    }

    @Test
    @DisplayName("타인의 주문 취소는 OrderNotFoundException으로 거절한다")
    void rejectsCancellationByAnotherMemberWithOrderNotFound() {
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-20260920-000001", "22222222-2222-4222-8222-222222222222"))
                .willReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                orderService.cancelBeforePayment(
                                        "OD-20260920-000001",
                                        "22222222-2222-4222-8222-222222222222"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("취소 상태 전이에 실패하면 OrderCannotBeCancelledException을 던진다")
    void rejectsCancellationWhenStateTransitionFails() {
        Order order = sampleOrder();
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
                .willReturn(Optional.of(order));
        given(orderRepository.cancelOrder(order.getId())).willReturn(0);

        assertThatThrownBy(
                        () ->
                                orderService.cancelBeforePayment(
                                        "OD-20260920-000001",
                                        "11111111-1111-4111-8111-111111111111"))
                .isInstanceOf(OrderCannotBeCancelledException.class);
    }

    @Test
    @DisplayName("취소 시 재고 복구에 실패하면 IllegalStateException을 던진다")
    void failsCancellationWhenStockCannotBeRestored() {
        Order order = sampleOrder();
        given(
                        orderRepository.findByOrderNumberAndMemberId(
                                "OD-20260920-000001", "11111111-1111-4111-8111-111111111111"))
                .willReturn(Optional.of(order));
        given(orderRepository.cancelOrder(order.getId())).willReturn(1);
        doThrow(new IllegalStateException("예약 재고 복원에 실패했습니다."))
                .when(salesService)
                .restoreReserved(order.getSalesInfoId(), order.getQuantity());

        assertThatThrownBy(
                        () ->
                                orderService.cancelBeforePayment(
                                        "OD-20260920-000001",
                                        "11111111-1111-4111-8111-111111111111"))
                .isInstanceOf(IllegalStateException.class);
    }
}
