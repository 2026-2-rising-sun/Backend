package com.shoppinglive.catalog.product.dto;

import com.shoppinglive.catalog.client.commercecore.SalesDisplayStatus;
import com.shoppinglive.catalog.product.entity.ProductCategory;

/**
 * 공개 상품 상세 응답. 가격·재고는 조회 시점 스냅샷이며 주문 생성 때 Commerce 가 다시 확정한다.
 *
 * @param price 단가 (원)
 * @param available 지연된 조회 사본의 예약 제외 재고. 실제 구매 가능 수량이나 구매 성공을 보장하지 않는다
 * @param purchasable 상품 전체 판매 허용(saleEnabled)과 공개 판매 상태(ON_SALE/SOLD_OUT)에 따른 진입 가능 표시. 최종 수량은
 *     Core가 판단한다
 * @param maxQuantity purchasable=true이면 입력 형식 상한 999999999, 아니면 0. 실제 재고 상한이 아니다
 */
public record PublicProductDetailResponse(
        Long productId,
        String name,
        String description,
        String mainImageUrl,
        long price,
        SalesDisplayStatus salesStatus,
        int available,
        boolean purchasable,
        int maxQuantity,
        ProductCategory category) {

    public static PublicProductDetailResponse from(PublicProductDetail detail) {
        return new PublicProductDetailResponse(
                detail.product().getId(),
                detail.product().getName(),
                detail.product().getDescription(),
                detail.mainImageUrl(),
                detail.salesInfo().price(),
                detail.salesStatus(),
                detail.salesInfo().available(),
                detail.purchasable(),
                detail.maxQuantity(),
                detail.product().getCategory());
    }
}
