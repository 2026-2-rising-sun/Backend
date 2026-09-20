package com.shoppinglive.commerce.orders.api;

import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 조회 REST 컨트롤러.
 *
 * <p>비회원 주문자가 주문번호 + {@code X-Order-Password} 헤더로 상세를 조회한다. 컨트롤러
 * 경로는 {@code /v1/...} 로 시작 (Infra Ingress 가 {@code /api/commerce/} prefix 벗김).
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderQueryController {

    private final OrderService orderService;

    public OrderQueryController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 주문 상세를 조회한다 (주문 3).
     *
     * @param orderNumber 외부 노출 주문 식별자
     * @param password 헤더 {@code X-Order-Password} 로 전달된 조회 비밀번호 원문
     */
    @GetMapping("/{orderNumber}")
    public OrderResponse getOrder(
        @PathVariable String orderNumber, @RequestHeader("X-Order-Password") String password) {
        Order order = orderService.findByOrderNumberAndPassword(orderNumber, password);
        return OrderResponse.from(order);
    }
}
