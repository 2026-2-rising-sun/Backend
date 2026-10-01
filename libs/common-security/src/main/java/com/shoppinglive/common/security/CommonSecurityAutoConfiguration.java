package com.shoppinglive.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** 경로별 SecurityFilterChain은 서비스 소유다. 이 구성은 인증 재료만 제공한다. */
@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration")
@EnableConfigurationProperties({JwtVerificationProperties.class, ServiceCallerProperties.class})
public class CommonSecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder memberJwtDecoder(JwtVerificationProperties properties) throws IOException, ParseException {
        try (InputStream input = properties.publicKeySetLocation().getInputStream()) {
            return MemberJwtDecoderFactory.create(JWKSet.load(input), properties.issuer(), properties.audience());
        }
    }

    @Bean
    MemberJwtAuthenticationConverter memberJwtAuthenticationConverter(AccessSessionVerifier sessions) {
        return new MemberJwtAuthenticationConverter(sessions);
    }

    @Bean
    TokenValidator tokenValidator(JwtDecoder decoder, MemberJwtAuthenticationConverter converter) {
        return new JwtTokenValidator(decoder, converter);
    }

    @Bean
    JsonSecurityErrorHandler jsonSecurityErrorHandler(ObjectMapper mapper) {
        return new JsonSecurityErrorHandler(mapper);
    }

    @Bean
    ServiceCallerTokenValidator serviceCallerTokenValidator(ServiceCallerProperties properties) {
        return new ServiceCallerTokenValidator(properties.serviceTokens());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(AccessSessionVerifier.class)
    @EnableConfigurationProperties(AccessSessionProperties.class)
    static class HttpSessionVerification {
        @Bean
        AccessSessionVerifier accessSessionVerifier(AccessSessionProperties properties, ObjectMapper mapper) {
            return new HttpAccessSessionVerifier(properties, mapper);
        }
    }
}
