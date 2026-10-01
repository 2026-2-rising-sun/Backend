package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.security.JwtVerificationProperties;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.member.auth.application.MemberTokenIssuer;
import com.shoppinglive.member.auth.config.MemberAuthConfiguration;
import com.shoppinglive.member.auth.config.MemberTokenProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

class MemberTokenConfigurationTest {
    @TempDir Path directory;

    @Test
    void requiresExplicitPositiveWholeSecondTokenLifetimes() {
        var file = new FileSystemResource(directory.resolve("private.pem"));
        for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500)}) {
            assertThatThrownBy(() -> new MemberTokenProperties(file, "kid", invalid, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MemberTokenProperties(file, "kid", Duration.ofMinutes(15), invalid))
                .isInstanceOf(IllegalArgumentException.class);
        }
        new ApplicationContextRunner().withUserConfiguration(MemberAuthConfiguration.class)
            .withPropertyValues("member.auth.private-key-location=" + directory.resolve("private.pem").toUri(), "member.auth.key-id=test-key")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesUnknownKeyIdAndMismatchedPrivateKeyBeforeIssuing() throws Exception {
        var keys = new JwtTestTokens();
        var other = new JwtTestTokens();
        Path publicFile = directory.resolve("public.jwks");
        Path privateFile = directory.resolve("private.pem");
        Files.writeString(publicFile, keys.publicJwkSet().toString());
        Files.writeString(privateFile, "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(other.privateKeyPkcs8())
            + "\n-----END PRIVATE KEY-----\n");
        var verification = new JwtVerificationProperties(JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE,
            new FileSystemResource(publicFile));
        for (String kid : new String[] {keys.keyId(), "unknown"}) {
            var tokens = new MemberTokenProperties(new FileSystemResource(privateFile), kid,
                Duration.ofMinutes(15), Duration.ofDays(30));
            assertThatThrownBy(() -> new MemberTokenIssuer(tokens, verification)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
