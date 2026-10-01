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

/** No cache, retries or redirects: every authenticated request consults the authoritative Member state. */
public final class HttpAccessSessionVerifier implements AccessSessionVerifier {
    private final RestClient client;
    private final ObjectReader reader;

    public HttpAccessSessionVerifier(AccessSessionProperties properties, ObjectMapper mapper) {
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(properties.readTimeout());
        client = RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(requests)
            .defaultHeader("X-Service-Token", properties.serviceToken()).build();
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public boolean isActive(UUID memberId, UUID sessionId) {
        try {
            var response = client.post().uri("/v1/internal/auth/sessions/check")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body(Map.of("memberId", memberId, "sessionId", sessionId)).retrieve().toEntity(String.class);
            MediaType contentType = response.getHeaders().getContentType();
            if (response.getStatusCode().value() != 200 || response.getBody() == null
                || contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
                throw new IllegalStateException("Invalid session verification response");
            }
            JsonNode body = reader.readTree(response.getBody());
            if (body == null || !body.isObject() || !body.path("success").isBoolean()
                || !body.path("success").booleanValue() || !body.has("error") || !body.path("error").isNull()
                || !body.path("data").isObject() || !body.path("data").path("active").isBoolean()) {
                throw new IllegalStateException("Invalid session verification envelope");
            }
            return body.path("data").path("active").booleanValue();
        } catch (Exception exception) {
            // Do not expose upstream bodies or credentials in the public response.
            throw new AccessSessionUnavailableException("Member session verification is unavailable", exception);
        }
    }
}
