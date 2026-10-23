package com.shoppinglive.common.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class ObservabilityConfiguration {
    @Bean
    BusinessTelemetry businessTelemetry(io.micrometer.core.instrument.MeterRegistry registry) {
        return new BusinessTelemetry(registry);
    }

    @Bean
    DomainEventObservation domainEventObservation(BusinessTelemetry telemetry) {
        return new DomainEventObservation(telemetry);
    }

    @Bean
    @Order(0)
    SecurityFilterChain metricsSecurity(
            HttpSecurity http,
            @Value("${management.server.port:0}") int managementPort,
            @Value("${server.port:8080}") int applicationPort)
            throws Exception {
        // 별도 관리 포트에서만 읽도록 제한한다. Service/Ingress는 이 포트를 노출하지 않는다.
        return http.securityMatcher("/actuator/prometheus")
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                request ->
                                                        managementPort > 0
                                                                && managementPort != applicationPort
                                                                && request.getLocalPort()
                                                                        == managementPort
                                                                && "GET"
                                                                        .equals(
                                                                                request
                                                                                        .getMethod()))
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .build();
    }

    @Bean
    RequestLogFilter requestLogFilter() {
        return new RequestLogFilter();
    }
}
