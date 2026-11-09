package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 기존 Core 정책 테스트용 상품 fixture. 운영 코드에는 포함되지 않으며 로컬 포트만 구현한다. */
@Component
@Primary
@ConditionalOnProperty(prefix = "commercecore.product.lookup", name = "mode", havingValue = "stub")
public class InMemoryProductLookupStub implements ProductLookup {

    private final ConcurrentMap<Long, ProductSnapshot> products = new ConcurrentHashMap<>();

    @Override
    public Optional<ProductSnapshot> findProduct(Long productId) {
        return Optional.ofNullable(products.get(productId));
    }

    /** 테스트·개발용: stub 데이터 등록. */
    public void register(ProductSnapshot product) {
        if (product == null || product.id() == null) {
            throw new IllegalArgumentException("product와 product.id는 필수입니다.");
        }
        products.put(product.id(), product);
    }

    /** 테스트·개발용: stub 상태 초기화. */
    public void clear() {
        products.clear();
    }

    public int size() {
        return products.size();
    }
}
