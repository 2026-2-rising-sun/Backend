package com.shoppinglive.commerce.shopping.infrastructure;

import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 테스트 classpath에만 존재하는 {@link ShoppingClient} 인메모리 fixture.
 *
 * <p>테스트 설정에서 {@code commerce.shopping-client.mode=stub}을 명시해야 활성화된다.
 * 운영 jar에는 포함되지 않으므로 이 설정으로 실제 HTTP 호출을 우회할 수 없다.
 *
 * <p>동시성 안전: {@link ConcurrentHashMap} 기반. register/clear 는 test setup 에서 사용.
 *
 * <p>계약 시맨틱: 상품이 없으면 {@link Optional#empty()}. 이 stub 은 네트워크·5xx 상황이 없어
 * {@code ShoppingUnavailableException} 을 던지지 않는다. HTTP 구현체에서 그 계약을 검증.
 */
@Component
@Primary
@ConditionalOnProperty(
    prefix = "commerce.shopping-client",
    name = "mode",
    havingValue = "stub")
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
