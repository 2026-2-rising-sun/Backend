package com.shoppinglive.member.config;

import com.shoppinglive.common.security.JsonSecurityErrorHandler;
import com.shoppinglive.common.security.MemberJwtAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class MemberSecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain memberSecurity(HttpSecurity http, MemberJwtAuthenticationConverter converter,
                                      JsonSecurityErrorHandler errors) throws Exception {
        // 인증 수단은 직접 보낸 Bearer/JSON refresh뿐이다. Cookie/Basic/session 인증은 사용하지 않는다.
        return http.csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/auth/signup").permitAll()
                .requestMatchers(HttpMethod.GET, "/v1/members/me").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/v1/members/me").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .build();
    }
}
