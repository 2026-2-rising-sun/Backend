package com.shoppinglive.commerce.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real servlet requests expose sendError/ERROR redispatch that MockMvc does not execute. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:commerce_firewall;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
class FirewallRejectionHttpTest extends CommerceSecurityTestSupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    private Long saleId;

    @BeforeEach
    void seed() {
        saleId = sales.save(new Sales(123L, 1000L, SalesStatus.ON_SALE)).getId();
        stocks.save(new SalesStock(saleId, 10, 0));
    }

    @AfterEach
    void clean() {
        stocks.deleteAll();
        sales.deleteAll();
    }

    @Test
    void missingMiddleIdDoesNotBecomeAnAuthenticationFailure() throws Exception {
        for (String token : new String[] {null, adminBearer(), "Bearer invalid"}) {
            expect(request("GET", "/v1/sales//stock", token), 400, "INVALID_REQUEST");
            expect(request("PATCH", "/v1/sales//price", token), 400, "INVALID_REQUEST");
        }
        unchanged();
    }

    @Test
    void duplicateSlashesCannotBeNormalizedIntoAValidAdministratorMutation() throws Exception {
        expect(request("GET", "/v1//sales/" + saleId + "/stock", adminBearer()), 400, "INVALID_REQUEST");
        expect(request("PATCH", "/v1/sales//" + saleId + "/price", adminBearer()), 400, "INVALID_REQUEST");
        unchanged();
    }

    @Test
    void encodedPercentRemainsRejectedByTheSecurityFirewall() throws Exception {
        expect(request("PATCH", "/v1/sales/%252F/price", adminBearer()), 400, "INVALID_REQUEST");
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%2F", "%2f"})
    void containerRejectsEncodedSlashesBeforeTheApplication(String slash) throws Exception {
        // Tomcat's default encoded-solidus rejection precedes all servlet filters and JSON handling.
        assertThat(request("PATCH", "/v1/sales/" + slash + "/price", adminBearer()).statusCode()).isEqualTo(400);
        unchanged();
    }

    @Test
    void normalRoutesKeepAuthenticationAuthorizationAndAdministratorSuccess() throws Exception {
        String path = "/v1/sales/" + saleId + "/stock";
        expect(request("GET", path, null), 401, "UNAUTHORIZED");
        expect(request("GET", path, "Bearer invalid"), 401, "UNAUTHORIZED");
        expect(request("GET", path, bearer(MEMBER_A)), 403, "FORBIDDEN");
        assertThat(request("GET", path, adminBearer()).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        unchanged();
    }

    private void unchanged() {
        assertThat(sales.count()).isEqualTo(1);
        assertThat(sales.findById(saleId).orElseThrow().getPrice()).isEqualTo(1000L);
        assertThat(stocks.findById(saleId).orElseThrow().getAvailable()).isEqualTo(10);
        assertThat(stocks.findById(saleId).orElseThrow().getReserved()).isZero();
    }

    private HttpResponse<String> request(String method, String path, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .method(method, method.equals("PATCH")
                ? HttpRequest.BodyPublishers.ofString("{\"price\":2000}") : HttpRequest.BodyPublishers.noBody());
        if (bearer != null) request.header("Authorization", bearer);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private void expect(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("error").path("requestId").asText()).isNotBlank()
            .isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        if (status == 400) assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }
}
