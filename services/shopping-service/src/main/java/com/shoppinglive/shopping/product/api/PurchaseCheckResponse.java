package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.product.application.PurchaseCheck;

/**
 * 구매 진입 수량 확인 응답. {@code orderable=false} 면 {@code reason} 이 SOLD_OUT 또는 EXCEEDS_STOCK 이다.
 *
 * @param unitPrice 조회 시점 단가 (원). 주문 생성 때 Commerce 가 다시 확정한다
 */
public record PurchaseCheckResponse(Long productId, int quantity, boolean orderable, PurchaseCheck.Reason reason,
        int maxQuantity, long unitPrice) {

    static PurchaseCheckResponse from(PurchaseCheck check) {
        return new PurchaseCheckResponse(check.productId(), check.quantity(), check.orderable(), check.reason(),
                check.maxQuantity(), check.unitPrice());
    }
}
