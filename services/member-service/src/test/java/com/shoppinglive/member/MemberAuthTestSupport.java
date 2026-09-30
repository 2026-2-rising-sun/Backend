package com.shoppinglive.member;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.member.members.infrastructure.MemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
abstract class MemberAuthTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected MemberRepository members;
    @Autowired protected PasswordEncoder passwords;
    @Autowired protected JdbcTemplate jdbc;
    protected static final JwtTestTokens TOKENS = new JwtTestTokens();
    private static final Path PUBLIC_KEYS = publicKeys();
    private static final Path PRIVATE_KEY = privateKey();
    protected static final String POSTGRES_SCHEMA = "member_test_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void resetMembers() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM refresh_families");
        members.deleteAll();
    }

    protected String signup(String email) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", "password123", "displayName", "member"));
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry properties) {
        properties.add("shoppinglive.security.jwt.issuer", () -> JwtTestTokens.ISSUER);
        properties.add("shoppinglive.security.jwt.audience", () -> JwtTestTokens.AUDIENCE);
        properties.add("shoppinglive.security.jwt.public-key-set-location", () -> PUBLIC_KEYS.toUri().toString());
        properties.add("member.auth.private-key-location", () -> PRIVATE_KEY.toUri().toString());
        properties.add("member.auth.key-id", TOKENS::keyId);
        // 테스트 입력일 뿐 운영 수명 정책을 정하지 않는다.
        properties.add("member.auth.access-token-ttl", () -> "PT15M");
        properties.add("member.auth.refresh-token-ttl", () -> "P30D");
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

    protected static Path privateKey() {
        try {
            Path file = Files.createTempFile("member-test-private-", ".pem");
            Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(TOKENS.privateKeyPkcs8())
                + "\n-----END PRIVATE KEY-----\n");
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
