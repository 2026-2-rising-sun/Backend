package com.shoppinglive.commerce.shopping.infrastructure;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShoppingClientProperties.class)
public class ShoppingClientConfiguration {

    @Bean
    HttpShoppingClient httpShoppingClient(
        RestClient.Builder builder,
        ShoppingClientProperties properties,
        CircuitBreakerRegistry circuitBreakers,
        RetryRegistry retries) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("commerce.shopping-client.base-url is required");
        }
        if (!StringUtils.hasText(properties.serviceToken()) || properties.serviceToken().length() < 32) {
            throw new IllegalStateException("commerce.shopping-client.service-token must contain at least 32 characters");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        RestClient client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory)
            .defaultHeader("X-Service-Token", properties.serviceToken()).build();
        return new HttpShoppingClient(client,
            circuitBreakers.circuitBreaker(HttpShoppingClient.RESILIENCE_INSTANCE),
            retries.retry(HttpShoppingClient.RESILIENCE_INSTANCE));
    }
}
