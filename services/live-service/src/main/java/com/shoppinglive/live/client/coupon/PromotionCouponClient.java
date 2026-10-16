package com.shoppinglive.live.client.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PromotionCouponClient {
    private final RestClient client;
    private final ObjectMapper mapper;

    public PromotionCouponClient(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${live.products.commerce-url:}") String commerceUrl) {
        this.mapper = mapper;
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        client =
                commerceUrl.isBlank()
                        ? null
                        : builder.clone().baseUrl(commerceUrl).requestFactory(factory).build();
    }

    public JsonNode sellerCoupon(String authorization, String couponId) {
        return call(authorization, couponId, false).data();
    }

    public record Claim(JsonNode data, int status) {}

    public Claim claim(String authorization, String couponId) {
        return call(authorization, couponId, true);
    }

    private Claim call(String authorization, String couponId, boolean claim) {
        if (client == null) throw unavailable();
        try {
            var request =
                    claim
                            ? client.post().uri("/v1/coupons/{id}/claims", couponId)
                            : client.get().uri("/v1/seller/coupons/{id}", couponId);
            var response =
                    request.header("Authorization", authorization)
                            .retrieve()
                            .toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || !body.path("success").asBoolean() || !body.path("data").isObject())
                throw unavailable();
            return new Claim(body.path("data"), response.getStatusCode().value());
        } catch (RestClientResponseException error) {
            ErrorCode code =
                    switch (error.getStatusCode().value()) {
                        case 400 -> ErrorCode.INVALID_REQUEST;
                        case 401 -> ErrorCode.UNAUTHORIZED;
                        case 403 -> ErrorCode.FORBIDDEN;
                        case 404 -> ErrorCode.NOT_FOUND;
                        case 409 -> ErrorCode.CONFLICT;
                        default -> ErrorCode.SERVICE_UNAVAILABLE;
                    };
            String message = code.defaultMessage();
            if (code != ErrorCode.SERVICE_UNAVAILABLE) {
                try {
                    message =
                            mapper.readTree(error.getResponseBodyAsString())
                                    .path("error")
                                    .path("message")
                                    .asText(message);
                } catch (Exception ignored) {
                    /* 외부 오류 본문이 없으면 공통 오류 메시지를 사용한다. */
                }
            }
            throw new BusinessException(code, message);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE, "쿠폰 서비스를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
