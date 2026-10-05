package com.shoppinglive.member.operations;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Local fixtures use normal BCrypt login and session issuance, never an authentication bypass. */
@Component
@Profile("local & !dev & !prod & !production")
@ConditionalOnProperty(name = "member.local-test-accounts.enabled", havingValue = "true")
public class LocalTestAccounts implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    public LocalTestAccounts(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        create("user", "USER", "테스트 유저");
        create("seller", "SELLER", "테스트 판매자");
    }

    private void create(String login, String role, String displayName) {
        UUID id = UUID.nameUUIDFromBytes(("shoppinglive-local-" + login).getBytes(StandardCharsets.UTF_8));
        String email = login + "@local.test";
        var existing = jdbc.queryForList("SELECT id, password_hash, role, withdrawn_at FROM members WHERE email = ?", email);
        if (!existing.isEmpty()) {
            var member = existing.getFirst();
            if (!id.toString().equals(member.get("id").toString()) || !role.equals(member.get("role"))
                || member.get("withdrawn_at") != null || !passwords.matches(login, member.get("password_hash").toString())) {
                throw new IllegalStateException("Local test account collision; disable fixtures or use a disposable local database");
            }
            return;
        }
        jdbc.update("""
            INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at,version)
            VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
            """, id, email, passwords.encode(login), displayName, role);
    }
}
