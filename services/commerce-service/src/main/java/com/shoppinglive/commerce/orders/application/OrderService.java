package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 도메인 유스케이스 서비스.
 *
 * <p>이번 이슈(주문 3) 는 조회만 담당한다. 생성(주문 2) · 취소(주문 4) · 만료(주문 5) 는
 * 별도 이슈에서 이 클래스에 유스케이스를 추가한다.
 */
@Service
public class OrderService {

    private final OrderJpaRepository orderRepository;
    private final PasswordEncoder passwordEncoder;

    public OrderService(OrderJpaRepository orderRepository, PasswordEncoder passwordEncoder) {
        this.orderRepository = orderRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 주문번호와 조회 비밀번호로 주문을 조회한다.
     *
     * <p>존재하지 않는 주문번호 · 비밀번호 불일치를 모두 {@link OrderNotFoundException} 으로
     * 통합한다 (존재 여부 leak 방지).
     *
     * @param orderNumber 외부 노출 주문 식별자
     * @param rawPassword 사용자가 입력한 조회 비밀번호 원문
     * @throws OrderNotFoundException 주문 없음 · 비밀번호 불일치
     */
    @Transactional(readOnly = true)
    public Order findByOrderNumberAndPassword(String orderNumber, String rawPassword) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new OrderNotFoundException(
                "order not found or password mismatch: orderNumber=" + orderNumber));

        if (rawPassword == null
            || !passwordEncoder.matches(rawPassword, order.getLookupPasswordHash())) {
            throw new OrderNotFoundException(
                "order not found or password mismatch: orderNumber=" + orderNumber);
        }

        return order;
    }
}
