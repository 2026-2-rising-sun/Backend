package com.shoppinglive.shopping.product;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real servlet HTTP requests keep path matching and the production security filters in this regression. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:shopping_public_input;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
class PublicProductInputHttpTest extends ShoppingSecuritySupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/?productId=null", "/null", "/abc", "/%7BproductId%7D"})
    void missingOrMalformedIdIsAnAnonymousInputError(String suffix) throws Exception {
        JsonNode body = expect(request("GET", "/v1/products" + suffix, null), 400, "INVALID_REQUEST");
        if (suffix.equals("/")) {
            assertThat(body.path("error").path("message").asText()).isEqualTo("productId 는 필수입니다.");
        }
    }

    @Test
    void listAndUnknownNumericIdKeepTheirPublicResponses() throws Exception {
        HttpResponse<String> list = request("GET", "/v1/products", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(list.body()).path("success").asBoolean()).isTrue();
        expect(request("GET", "/v1/products/9", null), 404, "NOT_FOUND");
    }

    @Test
    void invalidBearerStillFailsAuthenticationOnPublicRoutes() throws Exception {
        for (String path : List.of("/v1/products", "/v1/products/", "/v1/products/null")) {
            expect(request("GET", path, "Bearer invalid"), 401, "UNAUTHORIZED");
        }
    }

    @Test
    void missingIdHandlingDoesNotOpenOtherMethodsOrProtectedRoutes() throws Exception {
        expect(request("POST", "/v1/products/", null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/admin/products", null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/admin/products", userBearer()), 403, "FORBIDDEN");
        expect(request("GET", "/v1/internal/products", null), 401, "UNAUTHORIZED");
    }

    private HttpResponse<String> request(String method, String path, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (bearer != null) request.header("Authorization", bearer);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        return body;
    }
}
