package com.shoppinglive.live.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.live.integration.commerce.HttpSalesClient;
import com.shoppinglive.live.integration.commerce.SalesStatus;
import com.shoppinglive.live.integration.shopping.HttpProductClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/** 실제 HTTP 로 Shopping 봉투/Commerce raw 계약과 오류 구분을 검증한다. */
class ProductClientsTest {
    private HttpServer server;
    private RestClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        client = ProductClientsConfiguration.http(RestClient.builder(),
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsShoppingWrapperAndCommerceRawBatchInOneCall() {
        respond("/v1/internal/products", 200,
            "{\"success\":true,\"data\":[{\"id\":1,\"name\":\"Coffee\",\"mainImageUrl\":null},"
                + "{\"id\":2,\"name\":\"Tea\",\"mainImageUrl\":\"/img/2.png\"}],\"error\":null}");
        respond("/v1/sales", 200,
            "[{\"productId\":1,\"salesId\":11,\"price\":1000,\"status\":\"ON_SALE\",\"available\":3},"
                + "{\"productId\":2,\"salesId\":12,\"price\":2000,\"status\":\"SOLD_OUT\",\"available\":0}]");

        assertThat(new HttpProductClient(client).get(List.of(1L, 2L)))
            .extracting("name").containsExactly("Coffee", "Tea");
        assertThat(new HttpSalesClient(client).get(List.of(1L, 2L)))
            .extracting("status").containsExactly(SalesStatus.ON_SALE, SalesStatus.SOLD_OUT);
    }

    @Test
    void missingProductIsNotFoundWhileUpstreamFailureIsUnavailable() {
        respond("/v1/internal/products", 200, "{\"success\":true,\"data\":[],\"error\":null}");
        respond("/v1/sales", 200, "[]");
        assertReason(() -> new HttpProductClient(client).get(1L),
            ProductLookupException.Reason.NOT_FOUND);
        assertReason(() -> new HttpSalesClient(client).get(1L),
            ProductLookupException.Reason.NOT_FOUND);

        server.removeContext("/v1/sales");
        respond("/v1/sales", 503, "{}");
        assertReason(() -> new HttpSalesClient(client).get(1L),
            ProductLookupException.Reason.UNAVAILABLE);
    }

    @Test
    void undeployedPathIsUnavailableNotEmpty() {
        assertReason(() -> new HttpSalesClient(client).get(1L),
            ProductLookupException.Reason.UNAVAILABLE);
        assertReason(() -> new HttpProductClient(client).get(1L),
            ProductLookupException.Reason.UNAVAILABLE);
    }

    @Test
    void rejectsUnknownStatusMissingFieldDuplicateAndForeignSales() {
        for (final String body : new String[] {
            "[{\"productId\":1,\"salesId\":2,\"price\":1000,\"status\":\"FUTURE\",\"available\":0}]",
            "[{\"productId\":1,\"salesId\":2,\"status\":\"ON_SALE\",\"available\":0}]",
            "[{\"productId\":9,\"salesId\":2,\"price\":1,\"status\":\"ON_SALE\",\"available\":0}]",
            "[{\"productId\":1,\"salesId\":2,\"price\":1,\"status\":\"ON_SALE\",\"available\":0},"
                + "{\"productId\":1,\"salesId\":3,\"price\":1,\"status\":\"ON_SALE\",\"available\":0}]",
            "[null]", "null", "invalid"}) {
            respond("/v1/sales", 200, body);
            assertReason(() -> new HttpSalesClient(client).get(List.of(1L)),
                ProductLookupException.Reason.UNAVAILABLE);
            server.removeContext("/v1/sales");
        }
    }

    @Test
    void rejectsMalformedOrForeignShoppingWrapper() {
        for (final String body : new String[] {"{}",
            "{\"success\":false,\"data\":[],\"error\":{\"code\":\"X\"}}",
            "{\"success\":true,\"data\":[{\"id\":1}]}",
            "{\"success\":true,\"data\":[{\"id\":9,\"name\":\"Other\"}]}", "invalid"}) {
            respond("/v1/internal/products", 200, body);
            assertReason(() -> new HttpProductClient(client).get(List.of(1L)),
                ProductLookupException.Reason.UNAVAILABLE);
            server.removeContext("/v1/internal/products");
        }
    }

    @Test
    void readTimeoutDoesNotFallBackToStub() {
        server.createContext("/v1/internal/products", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        assertReason(() -> new HttpProductClient(client).get(List.of(1L)),
            ProductLookupException.Reason.UNAVAILABLE);
    }

    @Test
    void batchIsCappedAtHundredSoNoNPlusOneFanOut() {
        final List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> new HttpProductClient(client).get(tooMany))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limited to 100");
    }

    @Test
    void stubRequiresLocalOrTestProfile() {
        final ProductClientsConfiguration configuration = new ProductClientsConfiguration();
        final ProductClientsConfiguration.Properties stub =
            new ProductClientsConfiguration.Properties("stub", null, null);
        assertThatThrownBy(() -> configuration.productClient(stub, RestClient.builder(),
            new MockEnvironment()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stub requires local or test profile");
    }

    @Test
    void httpModeRequiresUpstreamUrl() {
        final ProductClientsConfiguration configuration = new ProductClientsConfiguration();
        final ProductClientsConfiguration.Properties http =
            new ProductClientsConfiguration.Properties("http", null, null);
        assertThatThrownBy(() -> configuration.productClient(http, RestClient.builder(),
            new MockEnvironment()))
            .hasMessageContaining("URL is required");
    }

    private void respond(final String path, final int status, final String body) {
        server.createContext(path, exchange -> {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void assertReason(final Runnable runnable,
                              final ProductLookupException.Reason expected) {
        assertThatThrownBy(runnable::run)
            .isInstanceOf(ProductLookupException.class)
            .extracting(e -> ((ProductLookupException) e).reason())
            .isEqualTo(expected);
    }
}
