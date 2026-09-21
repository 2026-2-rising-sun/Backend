package com.shoppinglive.shopping.product.application;

import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;
import java.time.Instant;

/**
 * 관리용 목록의 상품 한 줄.
 *
 * @param price 판매정보가 없거나({@code NOT_REGISTERED}) 조회에 실패하면({@code UNKNOWN}) {@code null}
 * @param available price 와 같은 규칙
 */
public record AdminProductSummary(Long productId, String name, String mainImageUrl, Instant createdAt,
        SalesDisplayStatus salesStatus, Long price, Integer available) {
}
