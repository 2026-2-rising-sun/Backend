package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.application.SalesService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 도메인 유스케이스 서비스.
 *
 * <p>조회(주문 3) · 취소(주문 4). 생성·만료는 각 전용 서비스와 스케줄러에서 처리한다.
 */
@Service
public class OrderService {

    private final OrderJpaRepository orderRepository;
    private final SalesService salesService;
    private final PasswordEncoder passwordEncoder;

    public OrderService(
        OrderJpaRepository orderRepository,
        SalesService salesService,
        PasswordEncoder passwordEncoder) {
        this.orderRepository = orderRepository;
        this.salesService = salesService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 주문번호와 조회 비밀번호로 주문을 조회한다 (주문 3).
     *
     * <p>존재하지 않는 주문번호 · 비밀번호 불일치를 동일 404 로 통합 (leak 방지).
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

    /**
     * 결제 전 주문을 취소하고 재고를 복구한다 (주문 4).
     *
     * <p>흐름:
     * <ol>
     *   <li>비밀번호 검증 (실패 시 404 · leak 방지)</li>
     *   <li>조건부 UPDATE 로 PENDING_PAYMENT → CANCELLED (실패 시 409)</li>
     *   <li>재고 조건부 UPDATE 로 reserved → available 복구</li>
     * </ol>
     *
     * <p>1 단계 성공 트랜잭션만 3 단계로 진입하므로 재고는 정확히 한 번 복구된다. 취소·결제
     * 시작 동시 요청 시 한쪽만 성공 (조건부 UPDATE 시맨틱).
     *
     * @throws OrderNotFoundException 주문 없음 · 비밀번호 불일치 (404)
     * @throws OrderCannotBeCancelledException 결제 진행/완료 등 이미 다른 상태 (409)
     */
    @Transactional
    public void cancelBeforePayment(String orderNumber, String rawPassword) {
        Order order = findByOrderNumberAndPassword(orderNumber, rawPassword);

        int cancelled = orderRepository.cancelOrder(order.getId());
        if (cancelled == 0) {
            throw new OrderCannotBeCancelledException(orderNumber);
        }

        salesService.restoreReserved(order.getSalesInfoId(), order.getQuantity());
    }
}
