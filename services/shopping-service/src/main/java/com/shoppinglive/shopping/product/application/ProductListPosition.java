package com.shoppinglive.shopping.product.application;

import com.shoppinglive.shopping.product.domain.Product;
import java.time.Instant;
import java.util.Objects;

/** 최신 등록순 목록에서 마지막으로 살펴본 상품의 위치. 다음 조회는 이 위치 뒤(더 오래된 쪽)부터 한다. */
public record ProductListPosition(Instant createdAt, Long id) {

    public ProductListPosition {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
    }

    static ProductListPosition of(Product product) {
        return new ProductListPosition(product.getCreatedAt(), product.getId());
    }
}
