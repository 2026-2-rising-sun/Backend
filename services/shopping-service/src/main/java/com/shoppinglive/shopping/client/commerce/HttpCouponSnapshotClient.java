package com.shoppinglive.shopping.client.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.event.CouponInfoEventMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * commerce-service 의 Shopping 전용 쿠폰 스냅샷 API 호출 구현체 ({@code contracts/api/commerce-service.yaml}).
 *
 * <p>쿠폰 동기화만 쓴다. 상품 조회 요청마다 부르지 않으므로 retry·circuit breaker 를 두지 않는다. 실패하면 이번 동기화를 멈추고 다음 주기에 처음부터
 * 다시 한다.
 */
public class HttpCouponSnapshotClient {

    static final int PAGE_SIZE = 100;

    private final RestClient restClient;

    /**
     * @param restClient baseUrl·timeout·service token 이 설정된 클라이언트
     */
    public HttpCouponSnapshotClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 발급이 끝나지 않은 쿠폰 한 페이지 (쿠폰 ID 오름차순).
     *
     * @param afterId 이전 페이지의 {@code nextCursor}. 첫 페이지는 {@code null}
     */
    public Page page(String afterId) {
        JsonNode data =
                data(
                        restClient
                                .get()
                                .uri(
                                        uri -> {
                                            uri.path("/v1/internal/coupon-snapshots")
                                                    .queryParam("size", PAGE_SIZE);
                                            if (afterId != null) {
                                                uri.queryParam("afterId", afterId);
                                            }
                                            return uri.build();
                                        })
                                .accept(MediaType.APPLICATION_JSON)
                                .retrieve()
                                .body(JsonNode.class));
        JsonNode items = data.get("items");
        JsonNode hasNext = data.get("hasNext");
        JsonNode nextCursor = data.get("nextCursor");
        if (items == null || !items.isArray() || hasNext == null || !hasNext.isBoolean()) {
            throw new IllegalStateException("Commerce 쿠폰 목록 응답이 계약과 다릅니다.");
        }
        List<CouponSnapshot> snapshots = new ArrayList<>();
        for (JsonNode item : items) {
            snapshots.add(CouponInfoEventMessage.snapshot(item));
        }
        if (hasNext.booleanValue() && (nextCursor == null || !nextCursor.isTextual())) {
            throw new IllegalStateException("Commerce 쿠폰 목록에 nextCursor 가 없습니다.");
        }
        return new Page(snapshots, hasNext.booleanValue() ? nextCursor.textValue() : null);
    }

    /** 쿠폰 하나의 현재 상태. 종료된 쿠폰도 준다. Commerce 에 없으면 빈 값이다. */
    public Optional<CouponSnapshot> find(String couponId) {
        try {
            JsonNode data =
                    data(
                            restClient
                                    .get()
                                    .uri("/v1/internal/coupon-snapshots/{couponId}", couponId)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .retrieve()
                                    .body(JsonNode.class));
            return Optional.of(CouponInfoEventMessage.snapshot(data));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private static JsonNode data(JsonNode body) {
        if (body == null || !body.path("success").asBoolean(false) || !body.has("data")) {
            throw new IllegalStateException("Commerce 쿠폰 응답이 성공 응답이 아닙니다.");
        }
        return body.get("data");
    }

    /**
     * @param nextCursor 다음 페이지가 없으면 {@code null}
     */
    public record Page(List<CouponSnapshot> items, String nextCursor) {}
}
