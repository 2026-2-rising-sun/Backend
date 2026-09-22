package com.shoppinglive.commerce.orders.api;

import com.shoppinglive.commerce.orders.application.OrderCheckout;
import com.shoppinglive.commerce.sales.domain.SalesStatus;

/**
 * 주문서 확인 응답 (주문 1).
 *
 * <p>{@code orderable = false} 면 {@code reason} 이 채워진다. 화면은 이 값으로 "품절" · "재고
 * 부족" · "판매 중단" 을 구분해 안내한다.
 */
public record OrderCheckoutResponse(
    Long productId,
    Long salesId,
    String productName,
    long unitPrice,
    int quantity,
    long totalAmount,
    int available,
    SalesStatus salesStatus,
    boolean orderable,
    OrderCheckout.Reason reason
) {

    public static OrderCheckoutResponse from(OrderCheckout checkout) {
        return new OrderCheckoutResponse(
            checkout.productId(),
            checkout.salesId(),
            checkout.productName(),
            checkout.unitPrice(),
            checkout.quantity(),
            checkout.totalAmount(),
            checkout.available(),
            checkout.salesStatus(),
            checkout.orderable(),
            checkout.reason());
    }
}
