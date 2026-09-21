package com.shoppinglive.commerce.shopping.infrastructure;

import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 개발·통합 테스트용 {@link ShoppingClient} 인메모리 구현체.
 *
 * <p>{@code commerce.shopping-client.mode=stub} 일 때 (또는 property 미설정 default 시) 활성화.
 * Sprint 2 Phase 4 산출물. 화요일 페어링에서 {@code HttpShoppingClient} 로 교체 예정.
 *
 * <p>동시성 안전: {@link ConcurrentHashMap} 기반. register/clear 는 test setup 에서 사용.
 *
 * <p>계약 시맨틱: 상품이 없으면 {@link Optional#empty()}. 이 stub 은 네트워크·5xx 상황이 없어
 * {@code ShoppingUnavailableException} 을 던지지 않는다. HTTP 구현체에서 그 계약을 검증.
 */
@Component
@ConditionalOnProperty(
    prefix = "commerce.shopping-client",
    name = "mode",
    havingValue = "stub",
    matchIfMissing = true)
public class InMemoryShoppingClientStub implements ShoppingClient {

    private final ConcurrentMap<Long, ProductSnapshot> products = new ConcurrentHashMap<>();

    @Override
    public Optional<ProductSnapshot> findProduct(Long productId) {
        return Optional.ofNullable(products.get(productId));
    }

    /**
     * 테스트·개발용: stub 데이터 등록.
     */
    public void register(ProductSnapshot product) {
        if (product == null || product.id() == null) {
            throw new IllegalArgumentException("product and product.id must not be null");
        }
        products.put(product.id(), product);
    }

    /**
     * 테스트·개발용: stub 상태 초기화.
     */
    public void clear() {
        products.clear();
    }

    public int size() {
        return products.size();
    }
}
