package com.shoppinglive.commerce.orders.api;

import com.shoppinglive.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 REST 컨트롤러. 회원 본인 대상 조회·취소 API.
 *
 * <p>컨트롤러 경로는 {@code /v1/...} 로 시작. Infra Ingress 가 {@code /api/commerce/} prefix
 * 를 벗김.
 */
@RestController
@RequestMapping("/v1/orders")
public class OrderQueryController {

    private final OrderService orderService;

    public OrderQueryController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public OrderHistoryResponse history(@AuthenticationPrincipal AuthenticatedUser member,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = orderService.history(member.memberId(), page, size);
        return new OrderHistoryResponse(result.map(OrderResponse::from).getContent(), page, size,
            result.getTotalElements(), result.getTotalPages());
    }

    public record OrderHistoryResponse(List<OrderResponse> items, int page, int size,
        long totalElements, int totalPages) {}

    /**
     * 주문 상세를 조회한다 (주문 3).
     */
    @GetMapping("/{orderNumber}")
    public OrderResponse getOrder(
        @PathVariable String orderNumber, @AuthenticationPrincipal AuthenticatedUser member) {
        Order order = orderService.findByOrderNumberAndMemberId(orderNumber, member.memberId());
        return OrderResponse.from(order);
    }

    /**
     * 결제 전 주문을 취소한다 (주문 4). 재고 자동 복구.
     *
     * <p>이미 결제 진행 중이거나 확정·취소·만료된 주문은 409.
     */
    @PostMapping("/{orderNumber}/cancel")
    public ResponseEntity<Void> cancelOrder(
        @PathVariable String orderNumber, @AuthenticationPrincipal AuthenticatedUser member) {
        orderService.cancelBeforePayment(orderNumber, member.memberId());
        return ResponseEntity.noContent().build();
    }
}
