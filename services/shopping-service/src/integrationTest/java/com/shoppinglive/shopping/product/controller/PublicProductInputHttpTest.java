package com.shoppinglive.shopping.product.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 서블릿 HTTP 요청으로 경로 매칭과 운영용 보안 필터의 동작을 검증한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "spring.datasource.url=jdbc:h2:mem:shopping_public_input;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
class PublicProductInputHttpTest extends ShoppingSecuritySupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @DisplayName("공개 상품 경로의 ID 누락·잘못된 형식은 입력 오류다")
    @ParameterizedTest
    @ValueSource(strings = {"/", "/?productId=null", "/null", "/abc", "/%7BproductId%7D"})
    void missingOrMalformedIdIsAnAnonymousInputError(String suffix) throws Exception {
        JsonNode body =
                expect(request("GET", "/v1/products" + suffix, null), 400, "INVALID_REQUEST");
        if (suffix.equals("/")) {
            assertThat(body.path("error").path("message").asText()).isEqualTo("productId 는 필수입니다.");
        }
    }

    @DisplayName("상품 목록과 존재하지 않는 숫자 ID는 기존 공개 응답을 유지한다")
    @Test
    void listAndUnknownNumericIdKeepTheirPublicResponses() throws Exception {
        HttpResponse<String> list = request("GET", "/v1/products", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(list.body()).path("success").asBoolean()).isTrue();
        expect(request("GET", "/v1/products/9", null), 404, "NOT_FOUND");
        expect(request("GET", "/v1/product-images/9", null), 404, "NOT_FOUND");
    }

    @DisplayName("공개 이미지 경로의 ID 누락·잘못된 형식은 입력 오류다")
    @ParameterizedTest
    @ValueSource(strings = {"/", "/null", "/abc", "/%7BimageId%7D"})
    void missingOrMalformedImageIdIsAnAnonymousInputError(String suffix) throws Exception {
        expect(request("GET", "/v1/product-images" + suffix, null), 400, "INVALID_REQUEST");
    }

    @DisplayName("관리자 상품 경로는 ID 누락 검증 전에 인증을 확인한다")
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH"})
    void administratorMustAuthenticateBeforeMissingIdValidation(String method) throws Exception {
        expect(request(method, "/v1/admin/products/", adminBearer()), 400, "INVALID_REQUEST");
        expect(request(method, "/v1/admin/products/", null), 401, "UNAUTHORIZED");
        expect(request(method, "/v1/admin/products/", userBearer()), 403, "FORBIDDEN");
    }

    @DisplayName("내부 상품 경로의 ID가 없어도 올바른 서비스 인증을 요구한다")
    @Test
    void internalMissingIdStillRequiresTheCorrectServiceIdentity() throws Exception {
        expect(
                request("GET", "/v1/internal/products/", null, COMMERCE_KEY),
                400,
                "INVALID_REQUEST");
        expect(request("GET", "/v1/internal/products/", null, LIVE_KEY), 400, "INVALID_REQUEST");
        expect(request("GET", "/v1/internal/products/", null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/internal/products/", adminBearer()), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/internal/products/", null, OTHER_KEY), 403, "FORBIDDEN");
    }

    @DisplayName("공개 경로에서도 잘못된 Bearer 토큰은 인증 실패로 처리한다")
    @Test
    void invalidBearerStillFailsAuthenticationOnPublicRoutes() throws Exception {
        for (String path :
                List.of(
                        "/v1/products",
                        "/v1/products/",
                        "/v1/products/null",
                        "/v1/product-images/")) {
            expect(request("GET", path, "Bearer invalid"), 401, "UNAUTHORIZED");
        }
    }

    @DisplayName("ID 누락 처리가 다른 메서드나 보호된 경로를 공개하지 않는다")
    @Test
    void missingIdHandlingDoesNotOpenOtherMethodsOrProtectedRoutes() throws Exception {
        expect(request("POST", "/v1/products/", null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/admin/products", null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/admin/products", userBearer()), 403, "FORBIDDEN");
        expect(request("GET", "/v1/internal/products", null), 401, "UNAUTHORIZED");
    }

    @DisplayName("잘못된 업로드 미디어 타입은 기존 415 응답을 유지한다")
    @Test
    void invalidUploadMediaTypeKeepsItsDistinct415Response() throws Exception {
        expect(
                request("POST", "/v1/admin/product-images", adminBearer()),
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
