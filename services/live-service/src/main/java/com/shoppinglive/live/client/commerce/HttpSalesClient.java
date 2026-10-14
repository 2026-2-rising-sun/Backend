package com.shoppinglive.live.client.commerce;

import com.shoppinglive.live.client.ProductLookupException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Commerce 는 봉투 없이 raw 배열로 응답한다. Shopping parser 와 섞지 않는다. */
public record HttpSalesClient(RestClient client) implements SalesClient {
    private static final int MAX_BATCH = 100;

    @Override
    public List<SalesSnapshot> get(final List<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        if (productIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Commerce 일괄 조회의 최대 항목 수: " + MAX_BATCH);
        }
        final Set<Long> requested = new HashSet<>(productIds);
        try {
            final List<SalesSnapshot> response =
                    client.get()
                            .uri(
                                    uri ->
                                            uri.path("/v1/sales")
                                                    .queryParam(
                                                            "productIds",
                                                            productIds.stream()
                                                                    .map(String::valueOf)
                                                                    .toList())
                                                    .build())
                            .retrieve()
                            .body(new ParameterizedTypeReference<List<SalesSnapshot>>() {});
            if (response == null) {
                throw new IllegalStateException("Commerce 응답이 올바르지 않습니다.");
            }
            final Set<Long> seen = new HashSet<>();
            for (final SalesSnapshot sales : response) {
                if (sales == null
                        || !requested.contains(sales.productId())
                        || !seen.add(sales.productId())) {
                    throw new IllegalStateException("Commerce 응답이 올바르지 않습니다.");
                }
            }
            return response;
        } catch (RuntimeException e) {
            throw new ProductLookupException("commerce", ProductLookupException.Reason.UNAVAILABLE);
        }
    }
}
