package com.shoppinglive.commercecore.payment.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class PaymentClientConfiguration {
    @Bean
    com.shoppinglive.commercecore.payment.relay.PaymentRelayClient paymentRelayClient(
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            @Value(
                            "${commercecore.payment-relay.base-url:${commercecore.payment-client.base-url:http://localhost:8086}}")
                    String base,
            @Value(
                            "${commercecore.payment-client.service-token:${COMMERCECORE_PAYMENT_SERVICE_TOKEN:}}")
                    String token) {
        var preparation = new SimpleClientHttpRequestFactory();
        preparation.setConnectTimeout(Duration.ofSeconds(1));
        preparation.setReadTimeout(Duration.ofSeconds(3));
        var approval = new SimpleClientHttpRequestFactory();
        approval.setConnectTimeout(Duration.ofSeconds(1));
        approval.setReadTimeout(Duration.ofSeconds(10));
        return new com.shoppinglive.commercecore.payment.relay.HttpPaymentRelayClient(
                RestClient.builder()
                        .baseUrl(base)
                        .defaultHeader("X-Service-Token", token)
                        .requestFactory(preparation)
                        .build(),
                RestClient.builder()
                        .baseUrl(base)
                        .defaultHeader("X-Service-Token", token)
                        .requestFactory(approval)
                        .build(),
                mapper);
    }
}
