package com.shoppinglive.catalog.config;

import com.shoppinglive.common.security.JsonSecurityErrorHandler;
import com.shoppinglive.common.security.MemberJwtAuthenticationConverter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class CatalogSecurityConfiguration {
    @Bean
    @Order(2)
    SecurityFilterChain products(
            HttpSecurity http,
            MemberJwtAuthenticationConverter converter,
            JsonSecurityErrorHandler errors,
            Environment environment)
            throws Exception {
        return stateless(http, errors)
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(
                        auth -> {
                            auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                    .permitAll()
                                    .requestMatchers(
                                            HttpMethod.GET,
                                            "/actuator/health/readiness",
                                            "/actuator/health/liveness",
                                            "/v1/products",
                                            "/v1/products/",
                                            "/v1/products/{id}",
                                            "/v1/products/{id}/purchase-check",
                                            "/v1/products/{id}/coupons")
                                    .permitAll();
                            auth.anyRequest().denyAll();
                        })
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(errors)
                                        .accessDeniedHandler(errors)
                                        .withObjectPostProcessor(errors.bearerFailureHandler()))
                .build();
    }

    private HttpSecurity stateless(HttpSecurity http, JsonSecurityErrorHandler errors)
            throws Exception {
        // 명시적인 Bearer·서비스 헤더만 허용하고 쿠키로 인증하지 않는다.
        return http.csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(errors)
                                        .accessDeniedHandler(errors));
    }
}
