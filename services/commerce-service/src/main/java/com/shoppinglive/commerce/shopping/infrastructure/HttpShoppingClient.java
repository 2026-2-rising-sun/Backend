package com.shoppinglive.commerce.shopping.infrastructure;

import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.ApiResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Shopping 상품 단건 조회. 404만 미존재이며, 장애나 계약 위반을 미존재로 대체하지 않는다. */
public class HttpShoppingClient implements ShoppingClient {

    public static final String RESILIENCE_INSTANCE = "shoppingProducts";
    private static final ParameterizedTypeReference<ApiResponse<ProductSnapshot>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public HttpShoppingClient(RestClient restClient, CircuitBreaker circuitBreaker, Retry retry) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public Optional<ProductSnapshot> findProduct(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        Supplier<Optional<ProductSnapshot>> call = () -> fetch(productId);
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
        } catch (RuntimeException exception) {
            throw new ShoppingUnavailableException("shopping product lookup failed", exception);
        }
    }

    private Optional<ProductSnapshot> fetch(Long productId) {
        final ApiResponse<ProductSnapshot> response;
        try {
            response = restClient.get()
                .uri("/v1/internal/products/{id}", productId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(RESPONSE_TYPE);
        } catch (HttpClientErrorException.NotFound exception) {
            // 정상적인 미존재 응답은 retry/서킷 실패 집계의 대상이 아니다.
            return Optional.empty();
        }
        if (response == null || !response.success() || response.error() != null || response.data() == null) {
            throw new IllegalStateException("invalid shopping response envelope");
        }
        ProductSnapshot product = response.data();
        if (!productId.equals(product.id()) || product.id() <= 0 || !StringUtils.hasText(product.name())) {
            throw new IllegalStateException("invalid shopping product snapshot");
        }
        return Optional.of(product);
    }
}
