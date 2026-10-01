package com.shoppinglive.member;

import com.shoppinglive.common.security.test.JwtTestTokens;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

abstract class MemberAuthTestSupport {
    protected static final JwtTestTokens TOKENS = new JwtTestTokens();
    private static final Path PUBLIC_KEYS = publicKeys();
    protected static final String POSTGRES_SCHEMA = "member_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("shoppinglive.security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        properties.add("shoppinglive.security.jwt.audience", () -> JwtTestTokens.AUDIENCE);
        properties.add("shoppinglive.security.jwt.public-key-set-location", () -> PUBLIC_KEYS.toUri().toString());
        String databaseUrl = System.getenv("MEMBER_TEST_DB_URL");
        if (databaseUrl != null && databaseUrl.startsWith("jdbc:postgresql:")) {
            // URL의 기존 schema 선택과 무관하게 매 테스트 JVM이 만든 전용 schema만 사용한다.
            properties.add("spring.flyway.schemas", () -> POSTGRES_SCHEMA);
            properties.add("spring.flyway.default-schema", () -> POSTGRES_SCHEMA);
            properties.add("spring.jpa.properties.hibernate.default_schema", () -> POSTGRES_SCHEMA);
            properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + POSTGRES_SCHEMA);
        }
    }

    private static Path publicKeys() {
        try {
            Path file = Files.createTempFile("member-test-public-", ".jwks");
            Files.writeString(file, TOKENS.publicJwkSet().toString());
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
