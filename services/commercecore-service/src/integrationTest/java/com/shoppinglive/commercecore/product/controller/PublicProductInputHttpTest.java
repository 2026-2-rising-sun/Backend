package com.shoppinglive.commercecore.product.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.support.ProductSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 서블릿 HTTP 요청으로 경로 매칭과 운영용 보안 필터의 동작을 검증한다. */
@SpringBootTest(
        classes = com.shoppinglive.commercecore.product.support.ProductHttpTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "spring.datasource.url=jdbc:h2:mem:shopping_public_input;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
class PublicProductInputHttpTest extends ProductSecuritySupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @DisplayName("공개 이미지 경로의 ID 누락·잘못된 형식은 입력 오류다")
    @ParameterizedTest
    @ValueSource(strings = {"/", "/null", "/abc", "/%7BimageId%7D"})
    void missingOrMalformedImageIdIsAnAnonymousInputError(String suffix) throws Exception {
        expect(request("GET", "/v1/product-images" + suffix, null), 400, "INVALID_REQUEST");
    }

    @DisplayName("판매자 상품 경로는 ID 누락 검증 전에 인증을 확인한다")
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH"})
    void sellerMustAuthenticateBeforeMissingIdValidation(String method) throws Exception {
        expect(request(method, "/v1/seller/products/", sellerBearer()), 400, "INVALID_REQUEST");
        expect(request(method, "/v1/seller/products/", null), 401, "UNAUTHORIZED");
        expect(request(method, "/v1/seller/products/", userBearer()), 403, "FORBIDDEN");
    }

    @DisplayName("내부 상품 경로의 ID가 없어도 올바른 서비스 인증을 요구한다")
    @Test
    void internalMissingIdStillRequiresTheCorrectServiceIdentity() throws Exception {
        expect(request("GET", "/v1/internal/products/", null, LIVE_KEY), 400, "INVALID_REQUEST");
        expect(request("GET", "/v1/internal/products/", null, LIVE_KEY), 400, "INVALID_REQUEST");
        expect(request("GET", "/v1/internal/products/", null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/internal/products/", sellerBearer()), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/internal/products/", null, OTHER_KEY), 403, "FORBIDDEN");
    }

    @DisplayName("잘못된 업로드 미디어 타입은 기존 415 응답을 유지한다")
    @Test
    void invalidUploadMediaTypeKeepsItsDistinct415Response() throws Exception {
        expect(
                request("POST", "/v1/seller/product-images", sellerBearer()),
                415,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    private HttpResponse<String> request(String method, String path, String bearer)
            throws Exception {
        return request(method, path, bearer, null);
    }

    private HttpResponse<String> request(
            String method, String path, String bearer, String serviceToken) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (serviceToken != null) {
            request.header("X-Service-Token", serviceToken);
        }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode expect(HttpResponse<String> response, int status, String code)
            throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        return body;
    }
}
