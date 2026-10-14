package com.shoppinglive.commerce.client.shopping;

import com.shoppinglive.common.web.ServiceRestClientFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
            throw new IllegalStateException("commerce.shopping-client.base-url 설정이 필요합니다.");
        }
        if (!StringUtils.hasText(properties.serviceToken())
                || properties.serviceToken().length() < 32) {
            throw new IllegalStateException(
                    "commerce.shopping-client.service-token은 32자 이상이어야 합니다.");
        }
        RestClient client =
                ServiceRestClientFactory.create(
                        builder,
                        properties.baseUrl(),
                        properties.serviceToken(),
                        properties.connectTimeout(),
                        properties.readTimeout());
        return new HttpShoppingClient(
                client,
                circuitBreakers.circuitBreaker(HttpShoppingClient.RESILIENCE_INSTANCE),
                retries.retry(HttpShoppingClient.RESILIENCE_INSTANCE));
    }
}
