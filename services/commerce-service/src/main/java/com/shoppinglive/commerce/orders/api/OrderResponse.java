package com.shoppinglive.commerce.orders.api;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import java.time.Instant;

/**
 * 주문 조회 API 응답 DTO.
 *
 * <p>비밀번호 hash · 멱등키 · buyerPhone 등 민감·내부 필드는 노출하지 않는다. buyerName 만
 * 본인 확인용으로 표시.
 */
public record OrderResponse(
    String orderNumber,
    String productName,
    Integer quantity,
    Long unitPrice,
    Long totalAmount,
    OrderStatus status,
    String buyerName,
    Instant createdAt,
    Instant expiresAt,
    Instant cancelledAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
            order.getOrderNumber(),
            order.getProductNameSnapshot(),
            order.getQuantity(),
            order.getUnitPrice(),
            order.getTotalAmount(),
            order.getStatus(),
            order.getBuyerName(),
            order.getCreatedAt(),
            order.getExpiresAt(),
            order.getCancelledAt()
        );
    }
}
