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
import org.springframework.security.web.firewall.RequestRejectedHandler;

class CommonSecurityAutoConfigurationTest {
    @TempDir Path directory;
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(AccessSessionVerifier.class, () -> (member, session) -> true);

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
                .hasSingleBean(MemberJwtAuthenticationConverter.class).hasSingleBean(ServiceCallerTokenValidator.class)
                .hasSingleBean(RequestRejectedHandler.class));
        context.withPropertyValues(required).withPropertyValues(
            "shoppinglive.security.jwt.public-key-set-location=https://example.invalid/keys.jwks")
            .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void httpVerifierRequiresConfigurationAndDatabaseVerifierNeedsNoHttpProperties() throws Exception {
        Path keys = directory.resolve("session-public.jwks");
        Files.writeString(keys, new JwtTestTokens().publicJwkSet().toString());
        var candidate = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonSecurityAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("shoppinglive.security.jwt.issuer=shoppinglive-member",
                "shoppinglive.security.jwt.audience=shoppinglive-api",
                "shoppinglive.security.jwt.public-key-set-location=" + keys.toUri());
        candidate.run(result -> assertThat(result).hasFailed());
        String base = "shoppinglive.security.session.base-url=http://localhost:8081";
        String key = "shoppinglive.security.session.service-token=test-member-client-credential-at-least-32-characters";
        candidate.withPropertyValues(base).run(result -> assertThat(result).hasFailed());
        candidate.withPropertyValues(key).run(result -> assertThat(result).hasFailed());
        candidate.withPropertyValues(base, key).run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(AccessSessionVerifier.class);
            assertThat(result.getBean(AccessSessionVerifier.class)).isInstanceOf(HttpAccessSessionVerifier.class);
        });
        for (String invalid : new String[] {
            "shoppinglive.security.session.base-url=file:/etc/hosts",
            "shoppinglive.security.session.base-url=http://user:password@localhost:8081",
            "shoppinglive.security.session.base-url=http://localhost:8081/path",
            "shoppinglive.security.session.service-token=short",
            "shoppinglive.security.session.read-timeout=0s",
            "shoppinglive.security.session.connect-timeout=10s"}) {
            candidate.withPropertyValues(base, key, invalid).run(result -> assertThat(result).hasFailed());
        }
        candidate.withBean(AccessSessionVerifier.class, () -> (member, session) -> false)
            .run(result -> assertThat(result).hasNotFailed().hasSingleBean(AccessSessionVerifier.class)
                .doesNotHaveBean(AccessSessionProperties.class));
    }
}
