package com.shoppinglive.commercecore.product.event;

import com.shoppinglive.commercecore.product.entity.ProductCategory;
import java.time.Instant;

/** 상품 원본 전체 snapshot. version 0은 최초 등록이며 시간은 버전 비교에 쓰지 않는다. */
public record ProductInfoChangedEvent(
        long productId,
        String name,
        String description,
        ProductCategory category,
        long mainImageId,
        String mainImageUrl,
        String sellerId,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
