package com.shoppinglive.commerce.support;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.common.security.test.StubAccessSessionVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 임시 RSA 키와 실제 운영 검증기·필터를 사용해 인증을 검증한다. */
@Import(CommerceSecurityTestSupport.SessionTestConfiguration.class)
public abstract class CommerceSecurityTestSupport {
    @Autowired protected StubAccessSessionVerifier accessSessions;

    @BeforeEach
    void resetAccessSessionFixture() {
        accessSessions.reset();
    }

    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate fixtureJdbc;

    @AfterEach
    void removeOrphanPurchaseFixtures() {
        // 하위 테스트가 주문·결제를 먼저 지운 뒤 묶음 키를 정리해 활성·재요청 상태가 다음 테스트에 남지 않게 한다.
        if (fixtureJdbc != null && shouldCleanPurchaseFixtures()) {
            fixtureJdbc.update(
                    """
            DELETE FROM payment_group g WHERE NOT EXISTS
                (SELECT 1 FROM orders o WHERE o.payment_group_id = g.id)
            AND NOT EXISTS (SELECT 1 FROM payment_attempt p WHERE p.payment_group_id = g.id)
            """);
        }
    }

    protected boolean shouldCleanPurchaseFixtures() {
        return true;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionTestConfiguration {
        @Bean
        StubAccessSessionVerifier accessSessionVerifier() {
            return new StubAccessSessionVerifier();
        }
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
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("shoppinglive.security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        properties.add("shoppinglive.security.jwt.audience", () -> JwtTestTokens.AUDIENCE);
        properties.add(
                "shoppinglive.security.jwt.public-key-set-location",
                () -> KEY_SET.toUri().toString());
        properties.add("shoppinglive.security.service-tokens.shopping", () -> SHOPPING_TOKEN);
        properties.add("shoppinglive.security.service-tokens.live", () -> LIVE_TOKEN);
    }

    protected static String bearer(String memberId) {
        return "Bearer " + TOKENS.token(memberId, Set.of("USER"));
    }

    protected static String adminBearer() {
        return "Bearer " + TOKENS.token(SELLER, Set.of("USER", "SELLER"));
    }
}
