package com.shoppinglive.commercecore.product.support;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.common.security.test.StubAccessSessionVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제 서명과 운영 검증기·필터를 사용하며 인증 객체나 필터를 우회하지 않는다. */
@Import(ProductSecuritySupport.SessionTestConfiguration.class)
public abstract class ProductSecuritySupport {
    @Autowired protected StubAccessSessionVerifier accessSessions;

    @BeforeEach
    void resetAccessSessionFixture() {
        accessSessions.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SessionTestConfiguration {
        @Bean
        StubAccessSessionVerifier accessSessionVerifier() {
            return new StubAccessSessionVerifier();
        }
    }

    protected static final JwtTestTokens TOKENS = new JwtTestTokens();
    public static final String CATALOG_KEY = "test-catalog-commercecore-credential-00000001";
    public static final String LIVE_KEY = "test-live-shopping-credential-000000000001";
    protected static final String OTHER_KEY = "test-member-shopping-credential-0000000001";
    private static final String KEY_FILE = publicKeys();
    private static final java.nio.file.Path IMAGE_DIR = temporaryImages();

    private static java.nio.file.Path temporaryImages() {
        try {
            return Files.createTempDirectory("core-product-test-images-");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("commercecore.image.storage.dir", () -> IMAGE_DIR.toString());
        properties.add("shoppinglive.security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        properties.add("shoppinglive.security.jwt.audience", () -> JwtTestTokens.AUDIENCE);
        properties.add("shoppinglive.security.jwt.public-key-set-location", () -> KEY_FILE);
        properties.add("shoppinglive.security.service-tokens.catalog", () -> CATALOG_KEY);
        properties.add("shoppinglive.security.service-tokens.live", () -> LIVE_KEY);
        properties.add("shoppinglive.security.service-tokens.member", () -> OTHER_KEY);
    }

    public static String sellerBearer() {
        return "Bearer " + TOKENS.token(JwtTestTokens.SELLER, Set.of("SELLER"));
    }

    protected static String userBearer() {
        return "Bearer " + TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER"));
    }

    private static String publicKeys() {
        try {
            var file = Files.createTempFile("shopping-test-public-", ".jwks");
            Files.writeString(file, TOKENS.publicJwkSet().toString());
            file.toFile().deleteOnExit();
            return file.toUri().toString();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
