package com.shoppinglive.commerce.support;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.common.security.test.StubAccessSessionVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.BeforeEach;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Uses the production decoder and filters with a temporary RSA key; no mock authentication. */
@Import(CommerceSecurityTestSupport.SessionTestConfiguration.class)
public abstract class CommerceSecurityTestSupport {
    @Autowired protected StubAccessSessionVerifier accessSessions;

    @BeforeEach
    void resetAccessSessionFixture() { accessSessions.reset(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionTestConfiguration {
        @Bean StubAccessSessionVerifier accessSessionVerifier() { return new StubAccessSessionVerifier(); }
    }

    protected static final String MEMBER_A = "11111111-1111-4111-8111-111111111111";
    protected static final String MEMBER_B = "22222222-2222-4222-8222-222222222222";
    protected static final String SELLER = "33333333-3333-4333-8333-333333333333";
    protected static final String SHOPPING_TOKEN = "test-inbound-shopping-commerce-credential-32";
    protected static final String LIVE_TOKEN = "test-inbound-live-commerce-credential-32";
    protected static final JwtTestTokens TOKENS = new JwtTestTokens();
    private static final Path KEY_SET = keySet();

    private static Path keySet() {
        try {
            Path file = Files.createTempFile("commerce-jwt-test-", ".jwks");
            Files.writeString(file, TOKENS.publicJwkSet().toString());
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("shoppinglive.security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        properties.add("shoppinglive.security.jwt.audience", () -> JwtTestTokens.AUDIENCE);
        properties.add("shoppinglive.security.jwt.public-key-set-location", () -> KEY_SET.toUri().toString());
        properties.add("shoppinglive.security.service-tokens.shopping", () -> SHOPPING_TOKEN);
        properties.add("shoppinglive.security.service-tokens.live", () -> LIVE_TOKEN);
    }

    protected static String bearer(String memberId) { return "Bearer " + TOKENS.token(memberId, Set.of("USER")); }
    protected static String adminBearer() { return "Bearer " + TOKENS.token(SELLER, Set.of("USER", "SELLER")); }
}
