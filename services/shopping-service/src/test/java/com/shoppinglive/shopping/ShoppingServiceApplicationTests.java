package com.shoppinglive.shopping;

import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.infrastructure.HttpSalesInfoClient;
import com.shoppinglive.shopping.sales.infrastructure.SalesClientConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShoppingServiceApplicationTests {

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
            "shopping.sales-client.mode=stub")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SalesInfoClient.class);
                assertThat(context.getBean(SalesInfoClient.class)).isInstanceOf(HttpSalesInfoClient.class);
            });
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
