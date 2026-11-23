package com.shoppinglive.catalog.product.entity;

import com.shoppinglive.catalog.product.dto.PublicProductDetail;

/**
 * 주문 화면 진입 전 수량 사전 확인 결과. 사본 재고로 수량을 거절하지 않으며 Core 주문 생성이 최종 판단한다.
 *
 * @param reason 주문 불가 사유. 사전 확인을 허용하면 {@code null}
 */
public record PurchaseCheck(
        Long productId,
        int quantity,
        boolean orderable,
        Reason reason,
        int maxQuantity,
        long unitPrice) {

    public enum Reason {
        /** 상품 전체 판매가 중지됨. SKU 품절과 구분한다. */
        SALE_SUSPENDED,
        /** 품절이거나 판매중인데 재고가 0. */
        SOLD_OUT,
        /** 요청 수량이 주문 가능 재고보다 많다. */
        EXCEEDS_STOCK
    }

    public static PurchaseCheck of(PublicProductDetail detail, int quantity) {
        Reason reason = !detail.product().isSaleEnabled() ? Reason.SALE_SUSPENDED : null;
        return new PurchaseCheck(
                detail.product().getId(),
                quantity,
                reason == null,
                reason,
                reason == null ? 999999999 : 0,
                detail.salesInfo().price());
    }
}
