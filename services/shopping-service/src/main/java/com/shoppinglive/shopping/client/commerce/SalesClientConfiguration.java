package com.shoppinglive.shopping.client.commerce;

import com.shoppinglive.common.web.ServiceRestClientFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 판매정보 HTTP 어댑터 설정.
 *
 * <p>HTTP 구현체를 {@code @Component} 로 두지 않은 이유: timeout(request factory) 조립을 호출 로직과 분리해, 테스트가 {@code
 * MockRestServiceServer} 에 묶인 RestClient 를 그대로 넣을 수 있게 하려는 것이다 (구현체가 request factory 를 직접 설정하면 mock
 * 을 덮어쓴다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SalesClientProperties.class)
public class SalesClientConfiguration {

    @Bean
    HttpSalesInfoClient httpSalesInfoClient(
            RestClient.Builder restClientBuilder,
            SalesClientProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("shopping.sales-client.base-url 설정이 필요합니다.");
        }
        if (!StringUtils.hasText(properties.serviceToken())
                || properties.serviceToken().length() < 32
                || !properties.serviceToken().equals(properties.serviceToken().strip())) {
            throw new IllegalStateException("shopping.sales-client.service-token은 32자 이상이어야 합니다.");
        }
        RestClient restClient =
                ServiceRestClientFactory.create(
                        restClientBuilder,
                        properties.baseUrl(),
                        properties.serviceToken(),
                        properties.connectTimeout(),
                        properties.readTimeout());
        return new HttpSalesInfoClient(
                restClient,
                circuitBreakerRegistry.circuitBreaker(HttpSalesInfoClient.RESILIENCE_INSTANCE),
                retryRegistry.retry(HttpSalesInfoClient.RESILIENCE_INSTANCE));
    }
}
