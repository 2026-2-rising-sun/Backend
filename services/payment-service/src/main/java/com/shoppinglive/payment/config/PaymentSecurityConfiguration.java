package com.shoppinglive.payment.config;

import com.shoppinglive.common.security.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class PaymentSecurityConfiguration {
    @Bean
    ServiceCallerTokenValidator paymentCallers(
            @Value("${payment.core-service-token}") String token) {
        return new ServiceCallerTokenValidator(Map.of("commercecore", token));
    }

    @Bean
    JsonSecurityErrorHandler paymentSecurityErrors(
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new JsonSecurityErrorHandler(mapper);
    }

    @Bean
    @Order(1)
    SecurityFilterChain paymentSecurity(
            HttpSecurity http, ServiceCallerTokenValidator callers, JsonSecurityErrorHandler errors)
            throws Exception {
        return http.securityMatcher("/v1/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/v1/internal/payments",
                                                "/v1/internal/payments/*",
                                                "/v1/internal/payments/*/order-closure",
                                                "/v1/internal/refunds",
                                                "/v1/internal/refunds/*",
                                                "/v1/internal/payment-scenarios/*")
                                        .hasAuthority("SERVICE_commercecore")
                                        .anyRequest()
                                        .denyAll())
                .addFilterBefore(
                        new ServiceTokenAuthenticationFilter(callers, errors),
                        AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain probes(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .build();
    }
}
