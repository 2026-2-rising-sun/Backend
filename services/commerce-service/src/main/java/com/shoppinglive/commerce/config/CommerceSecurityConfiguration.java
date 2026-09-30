package com.shoppinglive.commerce.config;

import com.shoppinglive.common.security.JsonSecurityErrorHandler;
import com.shoppinglive.common.security.MemberJwtAuthenticationConverter;
import com.shoppinglive.common.security.ServiceCallerTokenValidator;
import com.shoppinglive.common.security.ServiceTokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class CommerceSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain internalSales(HttpSecurity http, ServiceCallerTokenValidator callers,
        JsonSecurityErrorHandler errors) throws Exception {
        return http.securityMatcher(request -> "GET".equals(request.getMethod())
                && "/v1/sales".equals(request.getRequestURI().substring(request.getContextPath().length())))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/v1/sales")
                .hasAnyAuthority("SERVICE_shopping", "SERVICE_live").anyRequest().denyAll())
            .addFilterBefore(new ServiceTokenAuthenticationFilter(callers, errors), AnonymousAuthenticationFilter.class)
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain memberCommerce(HttpSecurity http, MemberJwtAuthenticationConverter converter,
        JsonSecurityErrorHandler errors, Environment environment) throws Exception {
        boolean scenarios = environment.acceptsProfiles(Profiles.of("(local | test) & !dev & !prod"))
            && environment.getProperty("commerce.dev.payment-scenario.enabled", Boolean.class, false);
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(handler -> handler.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                    .requestMatchers("/v1/orders", "/v1/orders/**", "/v1/cart/items", "/v1/cart/items/**")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.POST, "/v1/sales").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/v1/sales/*/price", "/v1/sales/*/stock", "/v1/sales/*/status").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/v1/sales/*/stock").hasRole("ADMIN");
                if (scenarios) auth.requestMatchers("/v1/dev/payment-scenarios/**").hasRole("ADMIN");
                auth.anyRequest().denyAll();
            })
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .build();
    }
}
