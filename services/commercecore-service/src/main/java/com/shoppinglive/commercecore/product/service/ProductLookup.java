package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** CommerceCore 상품 원본을 로컬에서 조회하는 포트. */
public interface ProductLookup {
    Optional<ProductSnapshot> findProduct(Long productId);

    /** 없는 상품을 제외하고 중복 없는 요청 순서를 유지한다. */
    default List<ProductSnapshot> findProducts(Collection<Long> productIds) {
        return productIds.stream()
                .distinct()
                .map(this::findProduct)
                .flatMap(Optional::stream)
                .toList();
    }

    default boolean exists(Long productId) {
        return findProduct(productId).isPresent();
    }
}
