package com.shoppinglive.shopping.client.commerce;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * commerce-service {@code GET /v1/sales?productIds=} 호출 구현체. 계약 전문은 <a
 * href="https://app.notion.com/p/3ea226545d15810f8585c03901b257f3">Notion 서비스 간 API 계약</a>을 기준으로
 * 관리한다.
 *
 * <p>한 요청 최대 {@value #MAX_IDS_PER_REQUEST} 개라 나눠 호출하고 합친다. 조각 하나라도 실패하면 전체 실패다 (일부만 UNKNOWN 이 섞인
 * 화면보다 명확한 재시도 안내가 낫다). 요청마다 Retry(CircuitBreaker(호출)) 로 감싸 재시도마다 서킷이 실패를 기록하고, 서킷이 열리면 재시도 없이 즉시
 * 실패한다. 어떤 실패든 {@link SalesInfoUnavailableException} 하나로만 드러낸다.
 */
public class HttpSalesInfoClient implements SalesInfoClient {

    /** application.yml 의 resilience4j circuitbreaker/retry 인스턴스 이름. */
    public static final String RESILIENCE_INSTANCE = "commerceSales";

    static final int MAX_IDS_PER_REQUEST = 100;

    private static final Logger log = LoggerFactory.getLogger(HttpSalesInfoClient.class);
    private static final ParameterizedTypeReference<List<CommerceSalesResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    /**
     * @param restClient baseUrl·timeout 이 설정된 클라이언트
     */
    public HttpSalesInfoClient(RestClient restClient, CircuitBreaker circuitBreaker, Retry retry) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public Map<Long, SalesInfo> findByProductIds(Collection<Long> productIds) {
        Set<Long> ids = ProductIds.distinct(productIds);
        List<Long> idList = new ArrayList<>(ids);
        Map<Long, SalesInfo> found = new HashMap<>();
        for (int from = 0; from < idList.size(); from += MAX_IDS_PER_REQUEST) {
            List<Long> chunk =
                    idList.subList(from, Math.min(from + MAX_IDS_PER_REQUEST, idList.size()));
            for (CommerceSalesResponse response : fetch(chunk)) {
                SalesInfo info = toSalesInfo(response);
                // 요청하지 않은 상품이 섞여 와도 결과는 요청 범위로 제한한다.
                if (ids.contains(info.productId()) && found.put(info.productId(), info) != null) {
                    // 계약상 상품당 판매정보는 1개다. 중복이 오면 어느 값이 맞는지 알 수 없으므로 실패로 본다.
                    throw new SalesInfoUnavailableException(
                            "Commerce 판매정보 응답에 중복된 productId가 있습니다: " + info.productId());
                }
            }
        }
        return Map.copyOf(found);
    }

    private List<CommerceSalesResponse> fetch(List<Long> chunk) {
        String joined = chunk.stream().map(String::valueOf).collect(Collectors.joining(","));
        Supplier<List<CommerceSalesResponse>> call =
                () -> {
                    List<CommerceSalesResponse> body =
                            restClient
                                    .get()
                                    .uri(
                                            uri ->
                                                    uri.path("/v1/sales")
                                                            .queryParam("productIds", joined)
                                                            .build())
                                    .accept(MediaType.APPLICATION_JSON)
                                    .retrieve()
                                    .body(RESPONSE_TYPE);
                    if (body == null) {
                        throw new IllegalStateException("Commerce 판매정보 응답 본문이 비어 있습니다.");
                    }
                    return body;
                };
        try {
            return Retry.decorateSupplier(
                            retry, CircuitBreaker.decorateSupplier(circuitBreaker, call))
                    .get();
        } catch (RuntimeException e) {
            log.warn("판매정보 조회 실패: 요청 상품 수={} 원인={}", chunk.size(), e.toString());
            throw new SalesInfoUnavailableException("Commerce 판매정보 조회에 실패했습니다.", e);
        }
    }

    private static SalesInfo toSalesInfo(CommerceSalesResponse response) {
        if (response == null) {
            throw new SalesInfoUnavailableException("Commerce 판매정보 응답에 null 항목이 있습니다.");
        }
        SalesStatus status = parseStatus(response.status());
        try {
            return new SalesInfo(
                    response.productId(),
                    response.salesId(),
                    response.price(),
                    status,
                    response.available());
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new SalesInfoUnavailableException("Commerce 판매정보 항목이 올바르지 않습니다: " + response, e);
        }
    }

    // 모르는 상태를 비슷한 값으로 추측하면 판매 불가 상품이 구매 가능으로 보일 수 있어 실패로 다룬다.
    private static SalesStatus parseStatus(String raw) {
        if (raw == null) {
            throw new SalesInfoUnavailableException("Commerce 판매 상태가 누락되었습니다.");
        }
        try {
            return SalesStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new SalesInfoUnavailableException("알 수 없는 Commerce 판매 상태입니다: " + raw, e);
        }
    }
}
