package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.product.domain.Product;
import java.time.Instant;

public record ProductResponse(Long productId, String name, String description, Long mainImageId, Long version,
        Instant createdAt) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(),
                product.getMainImageId(), product.getVersion(), product.getCreatedAt());
    }
}
