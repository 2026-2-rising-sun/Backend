package com.shoppinglive.member.operations;

import com.shoppinglive.member.members.api.SignupRequest;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 명시적 CLI로만 실행한다. HTTP endpoint, 애플리케이션 기동 hook, 기존 회원 승격 경로가 없다. */
public final class SellerBootstrapCommand {
    public static ConfigurableApplicationContext openNonWebContext() {
        SpringApplication application = new SpringApplication(SellerBootstrapCommand.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.main.web-application-type=none");
    }

    public UUID execute(Map<String, String> environment) {
        String url = required(environment, "MEMBER_BOOTSTRAP_DB_URL");
        String schema = required(environment, "MEMBER_BOOTSTRAP_DB_SCHEMA");
        String username = required(environment, "MEMBER_BOOTSTRAP_DB_USER");
        String databasePassword = required(environment, "MEMBER_BOOTSTRAP_DB_PASSWORD");
        if (!url.startsWith("jdbc:postgresql:") || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Seller bootstrap requires an explicit PostgreSQL URL and schema");
        }
        SignupRequest request = new SignupRequest(required(environment, "MEMBER_BOOTSTRAP_SELLER_EMAIL"),
            required(environment, "MEMBER_BOOTSTRAP_SELLER_PASSWORD"),
            required(environment, "MEMBER_BOOTSTRAP_SELLER_DISPLAY_NAME"));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            if (!validation.getValidator().validate(request).isEmpty()
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException("Invalid seller email, display name or password");
            }
        }
        String email = com.shoppinglive.member.members.domain.Member.normalizeEmail(request.email());
        String hash = new BCryptPasswordEncoder(12).encode(request.password());
        UUID id = UUID.randomUUID();
        // DB migration을 자동 실행하지 않는다. 운영자가 지정한 이미 준비된 schema에 신규 SELLER만 넣는다.
        try (var connection = DriverManager.getConnection(url, username, databasePassword)) {
            connection.setSchema(schema);
            try (var statement = connection.prepareStatement("""
                INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at,version)
                VALUES (?,?,?,?,'SELLER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """)) {
                statement.setObject(1, id);
                statement.setString(2, email);
                statement.setString(3, hash);
                statement.setString(4, request.displayName());
                statement.executeUpdate();
            }
            return id;
        } catch (SQLException exception) {
            // URL/SQL 오류가 자격증명을 포함할 수 있어 원문 예외와 환경 값을 출력하지 않는다.
            throw new IllegalStateException("Seller bootstrap failed; verify database access, migration and duplicate email");
        }
    }

    private String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required environment: " + key);
        return value;
    }
}
