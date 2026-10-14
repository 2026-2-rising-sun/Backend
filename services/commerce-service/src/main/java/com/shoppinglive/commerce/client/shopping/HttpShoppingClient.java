package com.shoppinglive.commerce.client.shopping;

import com.shoppinglive.common.core.ApiResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Shopping 상품 단건 조회. 404만 미존재이며, 장애나 계약 위반을 미존재로 대체하지 않는다. */
public class HttpShoppingClient implements ShoppingClient {

    public static final String RESILIENCE_INSTANCE = "shoppingProducts";
    private static final ParameterizedTypeReference<ApiResponse<ProductSnapshot>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<List<ProductSnapshot>>>
            LIST_RESPONSE_TYPE = new ParameterizedTypeReference<>() {};

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
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        Supplier<Optional<ProductSnapshot>> call = () -> fetch(productId);
        try {
            return Retry.decorateSupplier(
                            retry, CircuitBreaker.decorateSupplier(circuitBreaker, call))
                    .get();
        } catch (RuntimeException exception) {
            throw new ShoppingUnavailableException("Shopping 상품 조회에 실패했습니다.", exception);
        }
    }

    @Override
    public List<ProductSnapshot> findProducts(Collection<Long> productIds) {
        if (productIds == null
                || productIds.isEmpty()
                || productIds.size() > 100
                || productIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("productIds에는 양수인 ID를 1~100개 지정해야 합니다.");
        }
        List<Long> ids = productIds.stream().distinct().sorted().toList();
        Supplier<List<ProductSnapshot>> call =
                () -> {
                    ApiResponse<List<ProductSnapshot>> response =
                            restClient
                                    .get()
                                    .uri(
                                            uri ->
                                                    uri.path("/v1/internal/products")
                                                            .queryParam(
                                                                    "ids",
                                                                    ids.stream()
                                                                            .map(String::valueOf)
                                                                            .collect(
                                                                                    Collectors
                                                                                            .joining(
                                                                                                    ",")))
                                                            .build())
                                    .accept(MediaType.APPLICATION_JSON)
                                    .retrieve()
                                    .body(LIST_RESPONSE_TYPE);
                    if (response == null
                            || !response.success()
                            || response.error() != null
                            || response.data() == null) {
                        throw new IllegalStateException("Shopping 일괄 조회 응답의 공통 형식이 올바르지 않습니다.");
                    }
                    for (ProductSnapshot product : response.data()) {
                        if (product == null
                                || product.id() == null
                                || !ids.contains(product.id())
                                || product.id() <= 0
                                || !StringUtils.hasText(product.name())) {
                            throw new IllegalStateException("Shopping 상품 스냅샷이 올바르지 않습니다.");
                        }
                    }
                    return response.data();
                };
        try {
            return Retry.decorateSupplier(
                            retry, CircuitBreaker.decorateSupplier(circuitBreaker, call))
                    .get();
        } catch (RuntimeException exception) {
            throw new ShoppingUnavailableException("Shopping 상품 일괄 조회에 실패했습니다.", exception);
        }
    }

    private Optional<ProductSnapshot> fetch(Long productId) {
        final ApiResponse<ProductSnapshot> response;
        try {
            response =
                    restClient
                            .get()
                            .uri("/v1/internal/products/{id}", productId)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(RESPONSE_TYPE);
        } catch (HttpClientErrorException.NotFound exception) {
            // 정상적인 미존재 응답은 retry/서킷 실패 집계의 대상이 아니다.
            return Optional.empty();
        }
        if (response == null
                || !response.success()
                || response.error() != null
                || response.data() == null) {
            throw new IllegalStateException("Shopping 응답의 공통 형식이 올바르지 않습니다.");
        }
        ProductSnapshot product = response.data();
        if (!productId.equals(product.id())
                || product.id() <= 0
                || !StringUtils.hasText(product.name())) {
            throw new IllegalStateException("Shopping 상품 스냅샷이 올바르지 않습니다.");
        }
        return Optional.of(product);
    }
}
