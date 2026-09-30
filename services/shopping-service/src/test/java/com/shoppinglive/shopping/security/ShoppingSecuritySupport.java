package com.shoppinglive.shopping.security;

import com.shoppinglive.common.security.test.JwtTestTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Actual signatures and the production decoder/filter; no mocked Authentication or disabled filters. */
public abstract class ShoppingSecuritySupport {
    protected static final JwtTestTokens TOKENS = new JwtTestTokens();
    public static final String COMMERCE_KEY = "test-commerce-shopping-credential-00000001";
    public static final String LIVE_KEY = "test-live-shopping-credential-000000000001";
    protected static final String OTHER_KEY = "test-member-shopping-credential-0000000001";
    private static final String KEY_FILE = publicKeys();

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("shoppinglive.security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        properties.add("shoppinglive.security.jwt.audience", () -> JwtTestTokens.AUDIENCE);
        properties.add("shoppinglive.security.jwt.public-key-set-location", () -> KEY_FILE);
        properties.add("shoppinglive.security.service-tokens.commerce", () -> COMMERCE_KEY);
        properties.add("shoppinglive.security.service-tokens.live", () -> LIVE_KEY);
        properties.add("shoppinglive.security.service-tokens.member", () -> OTHER_KEY);
    }

    public static String adminBearer() { return "Bearer " + TOKENS.token(JwtTestTokens.ADMIN, Set.of("ADMIN")); }
    protected static String userBearer() { return "Bearer " + TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER")); }

    private static String publicKeys() {
        try {
            var file = Files.createTempFile("shopping-test-public-", ".jwks");
            Files.writeString(file, TOKENS.publicJwkSet().toString());
            file.toFile().deleteOnExit();
            return file.toUri().toString();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
}
