package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 과거 주문의 누락된 소유자 출처만 조회한다. 상품명·가격 등 현재 값으로 이력을 갱신하지 않는다. */
    default Map<Long, String> findHistoricalSellerIds(Collection<Long> productIds) {
        Map<Long, String> sellers = new HashMap<>();
        for (ProductSnapshot product : findProducts(productIds)) {
            if (product.sellerId() != null) sellers.put(product.id(), product.sellerId());
        }
        return sellers;
    }

    default boolean exists(Long productId) {
        return findProduct(productId).isPresent();
    }
}
