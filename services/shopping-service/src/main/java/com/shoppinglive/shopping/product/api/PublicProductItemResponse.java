package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;

/**
 * 공개 목록 한 줄.
 *
 * @param mainImageUrl 조회 시점에 만든 URL (DB 에 저장하지 않는다)
 * @param price        Commerce 판매정보의 표시 가격. 주문 시점에 Commerce 가 다시 확정한다
 * @param salesStatus  ON_SALE 또는 SOLD_OUT
 * @param purchasable  ON_SALE 이고 주문 가능 재고가 남았을 때만 true (공개 상세와 같은 규칙). 품절 상품도
 *                     노출하되 구매 버튼만 막고, 재고 0 인 판매중 상품이 목록에서만 구매 가능해 보이지 않게 한다
 */
public record PublicProductItemResponse(Long productId, String name, String mainImageUrl, long price,
        SalesDisplayStatus salesStatus, boolean purchasable) {
}
