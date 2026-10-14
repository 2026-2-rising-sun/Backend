package com.shoppinglive.common.documentation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** 문서를 제공하는 서비스에서만 명시적으로 import한다. 업무 경로의 권한은 소유하지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class DocumentationSecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain documentationSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
