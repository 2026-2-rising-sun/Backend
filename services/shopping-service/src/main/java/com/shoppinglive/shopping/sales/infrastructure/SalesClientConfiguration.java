package com.shoppinglive.shopping.sales.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 판매정보 어댑터 설정. stub 은 {@code @Component} 로 스스로 등록되고, HTTP 구현체는 여기서 만든다.
 *
 * <p>HTTP 구현체를 {@code @Component} 로 두지 않은 이유: timeout(request factory) 조립을 호출 로직과 분리해,
 * 테스트가 {@code MockRestServiceServer} 에 묶인 RestClient 를 그대로 넣을 수 있게 하려는 것이다
 * (구현체가 request factory 를 직접 설정하면 mock 을 덮어쓴다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SalesClientProperties.class)
public class SalesClientConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shopping.sales-client", name = "mode", havingValue = "http")
    HttpSalesInfoClient httpSalesInfoClient(
        RestClient.Builder restClientBuilder,
        SalesClientProperties properties,
        CircuitBreakerRegistry circuitBreakerRegistry,
        RetryRegistry retryRegistry) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("shopping.sales-client.base-url is required in http mode");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = restClientBuilder
            .baseUrl(properties.baseUrl())
            .requestFactory(requestFactory)
            .build();
        return new HttpSalesInfoClient(
            restClient,
            circuitBreakerRegistry.circuitBreaker(HttpSalesInfoClient.RESILIENCE_INSTANCE),
            retryRegistry.retry(HttpSalesInfoClient.RESILIENCE_INSTANCE));
    }
}
