package com.shoppinglive.member.config;

import com.shoppinglive.common.security.JsonSecurityErrorHandler;
import com.shoppinglive.common.security.MemberJwtAuthenticationConverter;
import com.shoppinglive.common.security.ServiceCallerTokenValidator;
import com.shoppinglive.common.security.ServiceTokenAuthenticationFilter;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class MemberSecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurity(HttpSecurity http, ServiceCallerTokenValidator callers,
                                         JsonSecurityErrorHandler errors) throws Exception {
        return http.securityMatcher("/v1/internal/**")
            .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ServiceTokenAuthenticationFilter(callers, errors), AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/v1/internal/auth/sessions/check")
                    .hasAnyAuthority("SERVICE_shopping", "SERVICE_commerce", "SERVICE_live")
                .anyRequest().denyAll())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors)).build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain memberSecurity(HttpSecurity http, MemberJwtAuthenticationConverter converter,
                                      JsonSecurityErrorHandler errors) throws Exception {
        // 인증 수단은 직접 보낸 Bearer/JSON refresh뿐이다. Cookie/Basic/session 인증은 사용하지 않는다.
        return http.csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/auth/signup", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/members/me").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/v1/members/me").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/v1/members/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/v1/admin/members/*/sessions/revoke").hasRole("ADMIN")
                .anyRequest().denyAll())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(errors).accessDeniedHandler(errors)
                .withObjectPostProcessor(errors.bearerFailureHandler()))
            .build();
    }
}
