package com.shoppinglive.commerce.client.shopping;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class ShoppingClientConfigurationTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(ShoppingClientConfiguration.class)
                    .withPropertyValues(
                            "commerce.shopping-client.service-token=test-outbound-commerce-shopping-token-32")
                    .withBean(RestClient.Builder.class, RestClient::builder)
                    .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
                    .withBean(RetryRegistry.class, RetryRegistry::ofDefaults);

    @DisplayName("운영 설정은 기존 mode 값과 무관하게 HTTP를 사용하고 stub으로 우회하지 않는다")
    @ParameterizedTest
    @ValueSource(strings = {"", "http", "stub"})
    void usesHttpInProductionRegardlessOfLegacyMode(String mode) {
        context.withPropertyValues(
                        "commerce.shopping-client.base-url=http://localhost:8082",
                        "commerce.shopping-client.mode=" + mode)
                .run(
                        application -> {
                            assertThat(application)
                                    .hasNotFailed()
                                    .hasSingleBean(ShoppingClient.class)
                                    .doesNotHaveBean(InMemoryShoppingClientStub.class);
                            assertThat(application.getBean(ShoppingClient.class))
                                    .isInstanceOf(HttpShoppingClient.class);
                        });
    }

    @DisplayName("mode가 없어도 HTTP 상품 클라이언트를 등록한다")
    @Test
    void usesHttpWhenModeIsMissing() {
        context.withPropertyValues("commerce.shopping-client.base-url=http://localhost:8082")
                .run(
                        application ->
                                assertThat(application)
                                        .hasNotFailed()
                                        .hasSingleBean(HttpShoppingClient.class));
    }

    @DisplayName("base-url이 없으면 프로필과 무관하게 기동에 실패한다")
    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "prod"})
    void failsStartupWithoutBaseUrlRegardlessOfProfile(String profile) {
        context.withPropertyValues("spring.profiles.active=" + profile)
                .run(application -> assertThat(application).hasFailed());
    }

    @DisplayName("무제한 또는 음수 제한 시간을 설정하면 기동에 실패한다")
    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s"})
    void rejectsUnlimitedAndNegativeTimeoutsAtStartup(String timeout) {
        context.withPropertyValues(
                        "commerce.shopping-client.base-url=http://localhost:8082",
                        "commerce.shopping-client.read-timeout=" + timeout)
                .run(application -> assertThat(application).hasFailed());
    }

    @DisplayName("테스트 fixture도 stub을 명시해야 선택한다")
    @Test
    void requiresExplicitStubModeForTestFixture() {
        context.withUserConfiguration(InMemoryShoppingClientStub.class)
                .withPropertyValues("commerce.shopping-client.base-url=http://127.0.0.1:1")
                .run(
                        application ->
                                assertThat(application)
                                        .doesNotHaveBean(InMemoryShoppingClientStub.class));
        context.withUserConfiguration(InMemoryShoppingClientStub.class)
                .withPropertyValues(
                        "commerce.shopping-client.base-url=http://127.0.0.1:1",
                        "commerce.shopping-client.mode=stub")
                .run(
                        application ->
                                assertThat(application.getBean(ShoppingClient.class))
                                        .isInstanceOf(InMemoryShoppingClientStub.class));
    }

    @DisplayName("서비스 토큰이 없거나 짧으면 기동에 실패한다")
    @ParameterizedTest
    @ValueSource(strings = {"", "short"})
    void failsStartupWithoutValidServiceToken(String token) {
        context.withPropertyValues(
                        "commerce.shopping-client.base-url=http://localhost:8082",
                        "commerce.shopping-client.service-token=" + token)
                .run(application -> assertThat(application).hasFailed());
    }
}
