package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.product.domain.Product;
import java.time.Instant;

/** @param mainImageUrl 조회 시점에 만든 URL (DB 에 저장하지 않는다) */
public record ProductResponse(Long productId, String name, String description, Long mainImageId,
        String mainImageUrl, Long version, Instant createdAt) {

    static ProductResponse of(Product product, String mainImageUrl) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getMainImageId(), mainImageUrl, product.getVersion(), product.getCreatedAt());
    }
}
