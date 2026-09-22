package com.shoppinglive.shopping.product.application;

import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;
import java.time.Instant;

/**
 * 관리용 상품 상세. {@code version} 은 기본정보 수정 요청의 낙관적 락 값으로 되돌려 보낸다.
 * price·available 은 {@link AdminProductSummary} 와 같은 규칙으로 {@code null} 일 수 있다.
 */
public record AdminProductDetail(Long productId, String name, String description, Long mainImageId,
        String mainImageUrl, Long version, Instant createdAt, Instant updatedAt,
        SalesDisplayStatus salesStatus, Long price, Integer available) {
}
