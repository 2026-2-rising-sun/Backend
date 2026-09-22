package com.shoppinglive.live.integration.shopping;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.integration.ProductLookupException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Shopping 은 ApiResponse 봉투로 응답한다. 봉투 검증 실패는 성공으로 소비하지 않는다. */
public record HttpProductClient(RestClient client) implements ProductClient {
    private static final int MAX_BATCH = 100;

    @Override
    public List<ProductSnapshot> get(final List<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        if (productIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Shopping batch is limited to " + MAX_BATCH);
        }
        final Set<Long> requested = new HashSet<>(productIds);
        try {
            final ApiResponse<List<ProductSnapshot>> response = client.get()
                .uri(uri -> uri.path("/v1/internal/products")
                    .queryParam("ids", productIds.stream().map(String::valueOf).toList())
                    .build())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<ProductSnapshot>>>() { });
            if (response == null || !response.success() || response.error() != null
                || response.data() == null) {
                throw new IllegalStateException("Invalid shopping response");
            }
            final Set<Long> seen = new HashSet<>();
            for (final ProductSnapshot product : response.data()) {
                if (product == null || !requested.contains(product.id())
                    || !seen.add(product.id())) {
                    throw new IllegalStateException("Invalid shopping response");
                }
            }
            return response.data();
        } catch (RuntimeException e) {
            throw new ProductLookupException("shopping",
                ProductLookupException.Reason.UNAVAILABLE);
        }
    }
}
