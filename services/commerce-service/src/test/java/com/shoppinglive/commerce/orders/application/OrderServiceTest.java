package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
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
            .isInstanceOf(OrderNotFoundException.class)
            .hasMessageContaining("OD-NOT-EXIST");
    }

    @Test
    void findByOrderNumberAndPassword_비밀번호_불일치도_OrderNotFoundException() {
        Order order = sampleOrder();
        given(orderRepository.findByOrderNumber("OD-20260920-000001"))
            .willReturn(Optional.of(order));
        given(passwordEncoder.matches("wrong", "hashed-password")).willReturn(false);

        // 존재하지 않는 주문번호 케이스와 동일 예외 · 동일 상태로 leak 방지
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
}
