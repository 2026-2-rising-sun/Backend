package com.shoppinglive.shopping.config;

import com.shoppinglive.common.security.JsonSecurityErrorHandler;
import com.shoppinglive.common.security.MemberJwtAuthenticationConverter;
import com.shoppinglive.common.security.ServiceCallerTokenValidator;
import com.shoppinglive.common.security.ServiceTokenAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class ShoppingSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain internalProducts(HttpSecurity http, ServiceCallerTokenValidator callers,
                                         JsonSecurityErrorHandler errors) throws Exception {
        return stateless(http, errors).securityMatcher("/v1/internal/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/v1/internal/products", "/v1/internal/products/{id}")
                .hasAnyAuthority("SERVICE_commerce", "SERVICE_live")
                .anyRequest().denyAll())
            .addFilterBefore(new ServiceTokenAuthenticationFilter(callers, errors), AnonymousAuthenticationFilter.class)
            .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain products(HttpSecurity http, MemberJwtAuthenticationConverter converter,
                                 JsonSecurityErrorHandler errors, Environment environment) throws Exception {
        boolean devEnabled = environment.acceptsProfiles(Profiles.of("(local | test) & !dev & !prod"))
            && environment.getProperty("shopping.dev-api.enabled", Boolean.class, false);
        return stateless(http, errors).cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health/readiness", "/actuator/health/liveness",
                        "/v1/products", "/v1/products/", "/v1/products/{id}", "/v1/products/{id}/purchase-check",
                        "/v1/product-images/{id}").permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/admin/products", "/v1/admin/products/{id}").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/v1/admin/products", "/v1/admin/product-images").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/v1/admin/products/{id}").hasRole("ADMIN");
                if (devEnabled) auth.requestMatchers(HttpMethod.DELETE, "/v1/dev/product-images").hasRole("ADMIN");
                auth.anyRequest().denyAll();
            })
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(errors).accessDeniedHandler(errors)
                .withObjectPostProcessor(errors.bearerFailureHandler()))
            .build();
    }

    private HttpSecurity stateless(HttpSecurity http, JsonSecurityErrorHandler errors) throws Exception {
        // Only explicit Bearer/service headers are accepted; this service never authenticates cookies.
        return http.csrf(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
    }
}
