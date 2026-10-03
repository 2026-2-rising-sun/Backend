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
    SecurityFilterChain broadcasts(HttpSecurity http, MemberJwtAuthenticationConverter converter,
                                    JsonSecurityErrorHandler errors) throws Exception {
        // Bearer headers only; Member owns JSON refresh tokens, and no authentication cookies are used.
        return http.csrf(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/readiness", "/actuator/health/liveness",
                    "/v1/broadcasts", "/v1/broadcasts/", "/v1/broadcasts/{id}", "/v1/broadcasts/{id}/products",
                    "/v1/broadcasts/{id}/chats", "/v1/broadcasts/{id}/events",
                    "/v1/broadcasts/{id}/likes").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/broadcasts/{id}/chats", "/v1/broadcasts/{id}/likes")
                    .hasAnyRole("USER", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/v1/admin/broadcasts", "/v1/admin/broadcasts/", "/v1/admin/broadcasts/{id}",
                    "/v1/admin/broadcasts/{id}/products").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/v1/admin/broadcasts", "/v1/admin/broadcasts/{id}/start",
                    "/v1/admin/broadcasts/{id}/end", "/v1/admin/broadcasts/{id}/products").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/v1/admin/broadcasts/", "/v1/admin/broadcasts/{id}").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/v1/admin/broadcasts/{id}/products/order").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/v1/admin/broadcasts/{id}/products/{linkId}").hasRole("ADMIN")
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(errors).accessDeniedHandler(errors)
                .withObjectPostProcessor(errors.bearerFailureHandler()))
            .build();
    }
}
