package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.live.integration.commerce.SalesSnapshot;
import com.shoppinglive.live.integration.shopping.ProductSnapshot;

/**
 * 방송 상품 조회 응답. FE 는 productId/salesId 를 그대로 일반 주문 흐름에 전달한다.
 * Live 전용 주문 API 는 없고 최종 가격·재고 판정은 Commerce 가 한다.
 * 관리 응답은 정상 조회에서 사라진 연결도 linkId/position 과 missing 으로 식별할 수 있다.
 */
public record BroadcastProductViewResponse(
    Long linkId,
    int position,
    Long productId,
    Long salesId,
    String name,
    String mainImageUrl,
    Long price,
    String status,
    boolean purchasable,
    boolean missing
) {
    public static BroadcastProductViewResponse of(final Long linkId, final int position,
                                                  final Long productId, final Long fallbackSalesId,
                                                  final ProductSnapshot product,
                                                  final SalesSnapshot sales) {
        if (product == null || sales == null) {
            return new BroadcastProductViewResponse(linkId, position, productId, fallbackSalesId,
                null, null, null, null, false, true);
        }
        return new BroadcastProductViewResponse(linkId, position, productId, sales.salesId(),
            product.name(), product.mainImageUrl(), sales.price(), sales.status().name(),
            // 품절은 구매 불가. ON_SALE 도 available 부족이면 Commerce 가 최종 거절한다.
            sales.status() == com.shoppinglive.live.integration.commerce.SalesStatus.ON_SALE
                && sales.available() > 0,
            false);
    }
}
