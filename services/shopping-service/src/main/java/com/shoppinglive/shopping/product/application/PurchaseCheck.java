package com.shoppinglive.shopping.product.application;

/**
 * 주문 화면 진입 전 수량 사전 확인 결과. 조회 시점 스냅샷일 뿐 재고를 잡아두지 않는다.
 *
 * @param reason 주문 불가 사유. 주문 가능하면 {@code null}
 */
public record PurchaseCheck(Long productId, int quantity, boolean orderable, Reason reason, int maxQuantity,
        long unitPrice) {

    public enum Reason {
        /** 품절이거나 판매중인데 재고가 0. */
        SOLD_OUT,
        /** 요청 수량이 주문 가능 재고보다 많다. */
        EXCEEDS_STOCK
    }

    static PurchaseCheck of(PublicProductDetail detail, int quantity) {
        Reason reason = !detail.purchasable() ? Reason.SOLD_OUT
                : quantity > detail.maxQuantity() ? Reason.EXCEEDS_STOCK
                : null;
        return new PurchaseCheck(detail.product().getId(), quantity, reason == null, reason, detail.maxQuantity(),
                detail.salesInfo().price());
    }
}
