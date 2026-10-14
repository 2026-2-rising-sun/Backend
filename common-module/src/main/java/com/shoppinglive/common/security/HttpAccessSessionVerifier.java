package com.shoppinglive.common.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 캐시·재시도·리다이렉트 없이 모든 인증 요청마다 Member의 최신 상태를 확인한다. */
public final class HttpAccessSessionVerifier implements AccessSessionVerifier {
    private final RestClient client;
    private final ObjectReader reader;

    public HttpAccessSessionVerifier(AccessSessionProperties properties, ObjectMapper mapper) {
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(properties.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(properties.readTimeout());
        client =
                RestClient.builder()
                        .baseUrl(properties.baseUrl().toString())
                        .requestFactory(requests)
                        .defaultHeader("X-Service-Token", properties.serviceToken())
                        .build();
        reader =
                mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public boolean isActive(UUID memberId, UUID sessionId) {
        try {
            var response =
                    client.post()
                            .uri("/v1/internal/auth/sessions/check")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .body(Map.of("memberId", memberId, "sessionId", sessionId))
                            .retrieve()
                            .toEntity(String.class);
            MediaType contentType = response.getHeaders().getContentType();
            if (response.getStatusCode().value() != 200
                    || response.getBody() == null
                    || contentType == null
                    || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                throw new IllegalStateException("세션 검증 응답이 올바르지 않습니다.");
            }
            JsonNode body = reader.readTree(response.getBody());
            if (body == null
                    || !body.isObject()
                    || !body.path("success").isBoolean()
                    || !body.path("success").booleanValue()
                    || !body.has("error")
                    || !body.path("error").isNull()
                    || !body.path("data").isObject()
                    || !body.path("data").path("active").isBoolean()) {
                throw new IllegalStateException("세션 검증 응답의 공통 형식이 올바르지 않습니다.");
            }
            return body.path("data").path("active").booleanValue();
        } catch (Exception exception) {
            // 외부 응답 본문이나 인증 정보를 사용자 응답에 노출하지 않는다.
            throw new AccessSessionUnavailableException("회원 세션 검증 서비스를 사용할 수 없습니다.", exception);
        }
    }
}
