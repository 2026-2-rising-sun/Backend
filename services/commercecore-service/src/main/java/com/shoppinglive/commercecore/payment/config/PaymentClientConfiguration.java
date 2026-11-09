package com.shoppinglive.commercecore.payment.config;

import com.shoppinglive.commercecore.payment.service.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PaymentClientConfiguration {
    @Bean
    PaymentClient paymentClient(
            @Value("${commercecore.payment-client.base-url:http://localhost:8086}") String base,
            @Value(
                            "${commercecore.payment-client.service-token:${COMMERCECORE_PAYMENT_SERVICE_TOKEN:}}")
                    String token,
            @Value("${commercecore.payment-client.connect-timeout:1s}") Duration connectTimeout,
            @Value("${commercecore.payment-client.read-timeout:3s}") Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new HttpPaymentClient(
                RestClient.builder()
                        .baseUrl(base)
                        .defaultHeader("X-Service-Token", token)
                        .requestFactory(factory)
                        .build());
    }
}
