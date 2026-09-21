package com.shoppinglive.shopping.sales.infrastructure;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 구현체들이 같은 입력 규칙(null 거부, 순서 유지 중복 제거)을 따르게 하는 유틸.
 */
final class ProductIds {

    private ProductIds() {
    }

    /** @throws IllegalArgumentException 컬렉션이나 원소가 {@code null} */
    static Set<Long> distinct(Collection<Long> productIds) {
        if (productIds == null) {
            throw new IllegalArgumentException("productIds must not be null");
        }
        Set<Long> distinct = new LinkedHashSet<>();
        // List.of(..).contains(null) 은 NPE 라 원소를 직접 검사한다.
        for (Long productId : productIds) {
            if (productId == null) {
                throw new IllegalArgumentException("productIds must not contain null");
            }
            distinct.add(productId);
        }
        return distinct;
    }
}
