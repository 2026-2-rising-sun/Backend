package com.shoppinglive.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class CommonSecurityAutoConfigurationTest {
    @TempDir Path directory;
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void failsStartupWithoutIssuerAudienceOrLocalKeyFile() throws Exception {
        Path keys = directory.resolve("public.jwks");
        Files.writeString(keys, new JwtTestTokens().publicJwkSet().toString());
        String[] required = {"shoppinglive.security.jwt.issuer=shoppinglive-member",
            "shoppinglive.security.jwt.audience=shoppinglive-api",
            "shoppinglive.security.jwt.public-key-set-location=" + keys.toUri()};
        for (int missing = 0; missing < required.length; missing++) {
            var candidate = context;
            for (int present = 0; present < required.length; present++) {
                if (present != missing) candidate = candidate.withPropertyValues(required[present]);
            }
            candidate.run(result -> assertThat(result).hasFailed());
        }
        context.withPropertyValues(required)
            .run(result -> assertThat(result).hasNotFailed().hasSingleBean(JwtDecoder.class)
                .hasSingleBean(MemberJwtAuthenticationConverter.class).hasSingleBean(ServiceCallerTokenValidator.class));
        context.withPropertyValues(required).withPropertyValues(
            "shoppinglive.security.jwt.public-key-set-location=https://example.invalid/keys.jwks")
            .run(result -> assertThat(result).hasFailed());
    }
}
