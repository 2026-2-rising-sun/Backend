package com.shoppinglive.catalog.product.support;

import com.shoppinglive.catalog.product.entity.Product;
import com.shoppinglive.catalog.product.entity.ProductCategory;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;

/** HTTP 원본 등록 대신 Core 식별자가 부여된 조회 사본을 준비한다. */
public final class ProductReplicaFixtures {
    private static final AtomicLong IDS = new AtomicLong(1000);

    private ProductReplicaFixtures() {}

    public static Product product(String name, String description, Long imageId, String seller) {
        return product(name, description, imageId, seller, null);
    }

    public static Product product(
            String name,
            String description,
            Long imageId,
            String seller,
            ProductCategory category) {
        Product p = org.springframework.beans.BeanUtils.instantiateClass(Product.class);
        ReflectionTestUtils.setField(p, "id", IDS.incrementAndGet());
        ReflectionTestUtils.setField(p, "name", name);
        ReflectionTestUtils.setField(p, "description", description);
        ReflectionTestUtils.setField(p, "mainImageId", imageId);
        ReflectionTestUtils.setField(
                p, "mainImageUrl", "/api/commercecore/v1/product-images/" + imageId);
        ReflectionTestUtils.setField(p, "sellerId", seller);
        ReflectionTestUtils.setField(p, "category", category);
        ReflectionTestUtils.setField(p, "version", 0L);
        ReflectionTestUtils.setField(p, "createdAt", Instant.now());
        ReflectionTestUtils.setField(p, "updatedAt", Instant.now());
        return p;
    }
}
