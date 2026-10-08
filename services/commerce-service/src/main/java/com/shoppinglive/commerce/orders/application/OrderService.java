package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.application.SalesService;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
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
    private final com.shoppinglive.commerce.purchase.application.PaymentGroupService groups;

    public OrderService(
        OrderJpaRepository orderRepository,
        SalesService salesService,
        com.shoppinglive.commerce.purchase.application.PaymentGroupService groups) {
        this.orderRepository = orderRepository;
        this.salesService = salesService;
        this.groups = groups;
    }

    @Transactional(readOnly = true)
    public Page<Order> history(String memberId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page >= 0, size 1~100 이어야 합니다.");
        }
        return orderRepository.findByMemberId(memberId, PageRequest.of(page, size,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    /**
     * 주문번호와 회원 식별자로 주문을 조회한다 (주문 3).
     *
     * <p>존재하지 않는 주문번호 · 다른 회원를 동일 404 로 통합 (leak 방지).
     */
    @Transactional(readOnly = true)
    public Order findByOrderNumberAndMemberId(String orderNumber, String memberId) {
        return orderRepository.findByOrderNumberAndMemberId(orderNumber, memberId)
            .orElseThrow(() -> new OrderNotFoundException("order not found: " + orderNumber));
    }

    /**
     * 결제 전 주문을 취소하고 재고를 복구한다 (주문 4).
     *
     * <p>흐름:
     * <ol>
     *   <li>본인 주문 확인 (실패 시 404 · leak 방지)</li>
     *   <li>조건부 UPDATE 로 PENDING_PAYMENT → CANCELLED (실패 시 409)</li>
     *   <li>재고 조건부 UPDATE 로 reserved → available 복구</li>
     * </ol>
     *
     * <p>1 단계 성공 트랜잭션만 3 단계로 진입하므로 재고는 정확히 한 번 복구된다. 취소·결제
     * 시작 동시 요청 시 한쪽만 성공 (조건부 UPDATE 시맨틱).
     *
     * @throws OrderNotFoundException 주문 없음 · 다른 회원 (404)
     * @throws OrderCannotBeCancelledException 결제 진행/완료 등 이미 다른 상태 (409)
     */
    @Transactional
    public void cancelBeforePayment(String orderNumber, String memberId) {
        Order order = findByOrderNumberAndMemberId(orderNumber, memberId);

        if (order.getPaymentGroup() != null) {
            if (orderRepository.findByPaymentGroupIdOrderByIdAsc(order.getPaymentGroup().getId()).size() != 1)
                throw new OrderCannotBeCancelledException(orderNumber);
            groups.cancel(memberId, order.getPaymentGroup().getGroupNumber());
            return;
        }
        int cancelled = orderRepository.cancelOrder(order.getId());
        if (cancelled == 0) {
            throw new OrderCannotBeCancelledException(orderNumber);
        }

        salesService.restoreReserved(order.getSalesInfoId(), order.getQuantity());
    }
}
