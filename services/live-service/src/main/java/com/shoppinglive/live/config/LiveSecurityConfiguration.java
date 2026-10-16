package com.shoppinglive.live.config;

import com.shoppinglive.common.security.JsonSecurityErrorHandler;
import com.shoppinglive.common.security.MemberJwtAuthenticationConverter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class LiveSecurityConfiguration {
    @Bean
    SecurityFilterChain broadcasts(
            HttpSecurity http,
            MemberJwtAuthenticationConverter converter,
            JsonSecurityErrorHandler errors)
            throws Exception {
        // Bearer 헤더로만 인증한다. 갱신 토큰은 Member의 JSON 요청으로 처리하고 인증 쿠키는 사용하지 않는다.
        return http.csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(errors)
                                        .accessDeniedHandler(errors))
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health/readiness",
                                                "/actuator/health/liveness",
                                                "/v1/broadcasts",
                                                "/v1/broadcasts/",
                                                "/v1/broadcasts/{id}",
                                                "/v1/broadcasts/{id}/products",
                                                "/v1/broadcasts/{id}/chats",
                                                "/v1/broadcasts/{id}/events",
                                                "/v1/broadcasts/{id}/likes",
                                                "/v1/broadcasts/{id}/viewers")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/v1/broadcasts/{id}/viewers/heartbeat")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET, "/v1/broadcasts/{id}/likes/mine")
                                        .hasAnyRole("USER", "SELLER")
                                        .requestMatchers(
                                                HttpMethod.PUT, "/v1/broadcasts/{id}/likes/mine")
                                        .hasAnyRole("USER", "SELLER")
                                        .requestMatchers(
                                                HttpMethod.POST, "/v1/broadcasts/{id}/chats")
                                        .hasAnyRole("USER", "SELLER")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/v1/seller/broadcasts",
                                                "/v1/seller/broadcasts/",
                                                "/v1/seller/broadcasts/{id}",
                                                "/v1/seller/broadcasts/{id}/products")
                                        .hasRole("SELLER")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/v1/seller/broadcasts",
                                                "/v1/seller/broadcasts/{id}/start",
                                                "/v1/seller/broadcasts/{id}/end",
                                                "/v1/seller/broadcasts/{id}/products")
                                        .hasRole("SELLER")
                                        .requestMatchers(
                                                HttpMethod.PATCH,
                                                "/v1/seller/broadcasts/",
                                                "/v1/seller/broadcasts/{id}")
                                        .hasRole("SELLER")
                                        .requestMatchers(
                                                HttpMethod.PUT,
                                                "/v1/seller/broadcasts/{id}/products/order")
                                        .hasRole("SELLER")
                                        .requestMatchers(
                                                HttpMethod.DELETE,
                                                "/v1/seller/broadcasts/{id}/products/{linkId}")
                                        .hasRole("SELLER")
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/v1/broadcasts/{broadcastId}/promotions")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/v1/seller/broadcasts/{broadcastId}/promotions")
                                        .hasRole("SELLER")
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/v1/seller/broadcasts/{broadcastId}/promotions",
                                                "/v1/seller/broadcasts/{broadcastId}/promotions/{promotionId}/open",
                                                "/v1/seller/broadcasts/{broadcastId}/promotions/{promotionId}/close")
                                        .hasRole("SELLER")
                                        .anyRequest()
                                        .denyAll())
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(errors)
                                        .accessDeniedHandler(errors)
                                        .withObjectPostProcessor(errors.bearerFailureHandler()))
                .build();
    }
}
