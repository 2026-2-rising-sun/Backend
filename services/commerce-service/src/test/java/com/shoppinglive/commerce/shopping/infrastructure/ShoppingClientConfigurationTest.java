package com.shoppinglive.commerce.shopping.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class ShoppingClientConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(ShoppingClientConfiguration.class)
        .withBean(RestClient.Builder.class, RestClient::builder)
        .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
        .withBean(RetryRegistry.class, RetryRegistry::ofDefaults);

    @ParameterizedTest
    @ValueSource(strings = {"", "http", "stub"})
    void 운영설정은_mode와_무관하게_HTTP이며_stub으로_우회하지_않는다(String mode) {
        context.withPropertyValues("commerce.shopping-client.base-url=http://localhost:8082",
                "commerce.shopping-client.mode=" + mode)
            .run(application -> {
                assertThat(application).hasNotFailed().hasSingleBean(ShoppingClient.class)
                    .doesNotHaveBean(InMemoryShoppingClientStub.class);
                assertThat(application.getBean(ShoppingClient.class)).isInstanceOf(HttpShoppingClient.class);
            });
    }

    @Test
    void mode가_없어도_HTTP로_등록한다() {
        context.withPropertyValues("commerce.shopping-client.base-url=http://localhost:8082")
            .run(application -> assertThat(application).hasNotFailed().hasSingleBean(HttpShoppingClient.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "prod"})
    void base_url이_없으면_프로필과_무관하게_기동실패한다(String profile) {
        context.withPropertyValues("spring.profiles.active=" + profile)
            .run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s"})
    void 무제한_timeout이나_음수_timeout은_기동실패한다(String timeout) {
        context.withPropertyValues("commerce.shopping-client.base-url=http://localhost:8082",
                "commerce.shopping-client.read-timeout=" + timeout)
            .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void 테스트_classpath의_fixture도_stub을_명시해야_선택된다() {
        context.withUserConfiguration(InMemoryShoppingClientStub.class)
            .withPropertyValues("commerce.shopping-client.base-url=http://127.0.0.1:1")
            .run(application -> assertThat(application).doesNotHaveBean(InMemoryShoppingClientStub.class));
        context.withUserConfiguration(InMemoryShoppingClientStub.class)
            .withPropertyValues("commerce.shopping-client.base-url=http://127.0.0.1:1",
                "commerce.shopping-client.mode=stub")
            .run(application -> assertThat(application.getBean(ShoppingClient.class))
                .isInstanceOf(InMemoryShoppingClientStub.class));
    }
}
