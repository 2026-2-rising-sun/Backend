package com.shoppinglive.shopping.sales.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 판매정보 어댑터 설정. {@code shopping.sales.source} 가 {@code http}(기본)면 Commerce HTTP 구현,
 * {@code replica} 면 {@code product_sales} 사본 구현이 {@code SalesInfoClient} 로 주입된다.
 *
 * <p>HTTP 구현체를 {@code @Component} 로 두지 않은 이유: timeout(request factory) 조립을 호출 로직과 분리해,
 * 테스트가 {@code MockRestServiceServer} 에 묶인 RestClient 를 그대로 넣을 수 있게 하려는 것이다
 * (구현체가 request factory 를 직접 설정하면 mock 을 덮어쓴다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SalesClientProperties.class)
public class SalesClientConfiguration {

    private static final String SOURCE_HTTP = "http";
    private static final String SOURCE_REPLICA = "replica";

    // Commerce 주소·서비스 토큰·timeout 은 판매정보와 상품 쿠폰이 함께 쓴다. 설정 검사도 여기서 기동을 막는다.
    @Bean
    RestClient commerceRestClient(RestClient.Builder restClientBuilder, SalesClientProperties properties) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("shopping.sales-client.base-url is required");
        }
        if (!StringUtils.hasText(properties.serviceToken()) || properties.serviceToken().length() < 32
            || !properties.serviceToken().equals(properties.serviceToken().strip())) {
            throw new IllegalStateException("shopping.sales-client.service-token requires at least 32 characters");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return restClientBuilder.clone()
            .baseUrl(properties.baseUrl())
            .defaultHeader("X-Service-Token", properties.serviceToken())
            .requestFactory(requestFactory)
            .build();
    }

    // HTTP 빈은 두 모드 모두 만든다. backfill 이 구체 타입으로 주입받고, source 검사도 여기서 해야 기동이 실패한다.
    @Bean
    HttpSalesInfoClient httpSalesInfoClient(
        RestClient commerceRestClient,
        CircuitBreakerRegistry circuitBreakerRegistry,
        RetryRegistry retryRegistry,
        @Value("${shopping.sales.source:" + SOURCE_HTTP + "}") String source) {
        // 조건 빈은 모르는 값을 조용히 무시해 http 로 떨어지므로, 허용 값 밖이면 여기서 기동을 막는다.
        if (!SOURCE_HTTP.equals(source) && !SOURCE_REPLICA.equals(source)) {
            throw new IllegalStateException("shopping.sales.source must be http or replica: " + source);
        }
        return new HttpSalesInfoClient(
            commerceRestClient,
            circuitBreakerRegistry.circuitBreaker(HttpSalesInfoClient.RESILIENCE_INSTANCE),
            retryRegistry.retry(HttpSalesInfoClient.RESILIENCE_INSTANCE));
    }

    // @Primary 로 SalesInfoClient 주입 후보를 하나로 모은다. http 모드에는 이 빈이 없어 기존 주입이 그대로다.
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "shopping.sales", name = "source", havingValue = SOURCE_REPLICA)
    ReplicaSalesInfoClient replicaSalesInfoClient(ProductSalesRepository replica) {
        return new ReplicaSalesInfoClient(replica);
    }
}
