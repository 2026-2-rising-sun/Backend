package com.shoppinglive.shopping;

import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.infrastructure.HttpSalesInfoClient;
import com.shoppinglive.shopping.sales.infrastructure.SalesClientConfiguration;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShoppingServiceApplicationTests extends ShoppingSecuritySupport {

    @Autowired SalesInfoClient salesInfoClient;

    @Test
    @DisplayName("기동한 서비스는 판매정보 조회에 HTTP 클라이언트를 사용한다")
    void contextLoads() {
        assertThat(salesInfoClient).isInstanceOf(HttpSalesInfoClient.class);
    }

    @Test
    @DisplayName("Commerce 주소가 없거나 공백이면 HTTP 빈 생성과 기동이 실패한다")
    void missingCommerceUrlPreventsStartup() {
        assertMissingUrlFails(salesContext());
        assertMissingUrlFails(salesContext().withPropertyValues("shopping.sales-client.base-url= "));
    }

    @Test
    @DisplayName("제거된 stub 설정이 남아 있어도 판매정보 조회는 HTTP만 사용한다")
    void legacyModeCannotEnableStub() {
        salesContext().withPropertyValues(
            "shopping.sales-client.base-url=http://localhost:8083",
            "shopping.sales-client.service-token=test-shopping-commerce-credential-00000001",
            "shopping.sales-client.mode=stub")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SalesInfoClient.class);
                assertThat(context.getBean(SalesInfoClient.class)).isInstanceOf(HttpSalesInfoClient.class);
            });
    }

    @Test
    void missingCredentialPreventsStartupAndConfiguredCredentialReachesCommerce() throws Exception {
        salesContext().withPropertyValues("shopping.sales-client.base-url=http://localhost:8083")
            .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("service-token requires"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var received = new AtomicReference<String>();
        server.createContext("/v1/sales", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write(new byte[] {'[', ']'});
            exchange.close();
        });
        server.start();
        try {
            salesContext().withPropertyValues("shopping.sales-client.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                "shopping.sales-client.service-token=test-shopping-commerce-credential-00000001")
                .run(context -> {
                    assertThat(context.getBean(SalesInfoClient.class).findByProductIds(List.of(1L))).isEmpty();
                    assertThat(received.get()).isEqualTo("test-shopping-commerce-credential-00000001");
                });
        } finally { server.stop(0); }
    }

    private static ApplicationContextRunner salesContext() {
        return new ApplicationContextRunner()
            .withUserConfiguration(SalesClientConfiguration.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
            .withBean(RetryRegistry.class, RetryRegistry::ofDefaults);
    }

    private static void assertMissingUrlFails(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("shopping.sales-client.base-url is required");
        });
    }
}
