package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.product.application.PublicProductDetail;
import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;

/**
 * 공개 상품 상세 응답. 가격·재고는 조회 시점 스냅샷이며 주문 생성 때 Commerce 가 다시 확정한다.
 *
 * @param price       단가 (원)
 * @param available   주문 가능 재고 (예약분 제외)
 * @param purchasable 판매중이고 재고가 있을 때만 true (품절은 보여주되 구매 불가)
 * @param maxQuantity 수량 선택 상한. 구매 불가면 0
 */
public record PublicProductDetailResponse(Long productId, String name, String description, String mainImageUrl,
        long price, SalesDisplayStatus salesStatus, int available, boolean purchasable, int maxQuantity) {

    static PublicProductDetailResponse from(PublicProductDetail detail) {
        return new PublicProductDetailResponse(detail.product().getId(), detail.product().getName(),
                detail.product().getDescription(), detail.mainImageUrl(), detail.salesInfo().price(),
                detail.salesStatus(), detail.salesInfo().available(), detail.purchasable(), detail.maxQuantity());
    }
}
