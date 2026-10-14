package com.shoppinglive.notification.config;

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

/**
 * 알림함은 전부 "내 것"만 다루므로 공개 경로가 없다. 상태 점검만 열어두고 나머지는 로그인을 요구한다.
 *
 * <p>SELLER 도 사용자이므로 두 역할 모두 허용한다. 끝의 denyAll 은 새 경로를 만들었는데 여기 등록을 잊었을 때 열린 채로 배포되지 않게 막는다.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationSecurityConfiguration {

    @Bean
    SecurityFilterChain notifications(
            HttpSecurity http,
            MemberJwtAuthenticationConverter converter,
            JsonSecurityErrorHandler errors)
            throws Exception {
        // Bearer 헤더만 쓴다. 인증 쿠키는 쓰지 않고, 리프레시 토큰은 Member 가 소유한다.
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
                                                "/actuator/health/liveness")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/v1/notifications",
                                                "/v1/notifications/",
                                                "/v1/notifications/unread-count")
                                        .hasAnyRole("USER", "SELLER")
                                        .requestMatchers(
                                                HttpMethod.POST, "/v1/notifications/{id}/read")
                                        .hasAnyRole("USER", "SELLER")
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
