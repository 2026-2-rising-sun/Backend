package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.auth.application.TokenPair;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reuses every session HTTP/rollback test against PostgreSQL, then tests real row-lock races. */
@Tag("postgres")
class MemberSessionPostgresTest extends MemberSessionTest {
    @Test
    void concurrentRefreshHasOneWinnerButReuseRevokesEvenTheWinningAccess() throws Exception {
        var initial = account("race@example.com");
        var result = race(() -> attemptRefresh(initial), () -> attemptRefresh(initial));
        assertThat(result.stream().filter(value -> value.token() != null).count()).isEqualTo(1);
        TokenPair winner = result.stream().map(Attempt::token).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        profile(winner).andExpect(status().isUnauthorized());
        profile(initial).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE used_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void refreshAndOrdinaryLogoutSerializeWithoutRevokingIssuedAccess() throws Exception {
        var initial = account("race@example.com");
        var result = race(() -> attemptRefresh(initial), () -> { sessionService.logout(initial.refreshToken()); return new Attempt(null); });
        profile(initial).andExpect(status().isOk());
        for (var value : result) if (value.token() != null) profile(value.token()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM refresh_families", java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT security_revoked_at FROM refresh_families", java.sql.Timestamp.class)).isNull();
    }

    @Test
    void refreshAndWithdrawalSerializeWithoutLeavingAnActiveFamily() throws Exception {
        var initial = account("race@example.com");
        UUID memberId = members.findByEmail("race@example.com").orElseThrow().getId();
        var result = race(() -> attemptRefresh(initial), () -> { sessionService.withdraw(memberId, "password123"); return new Attempt(null); });
        profile(initial).andExpect(status().isUnauthorized());
        for (var value : result) if (value.token() != null) profile(value.token()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM members", java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_families WHERE security_revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void refreshAndAdminRevocationCannotLeaveAnActiveFamily() throws Exception {
        var initial = account("race@example.com");
        UUID memberId = members.findByEmail("race@example.com").orElseThrow().getId();
        var result = race(() -> attemptRefresh(initial), () -> { sessionService.revokeAll(memberId); return new Attempt(null); });
        profile(initial).andExpect(status().isUnauthorized());
        for (var value : result) if (value.token() != null) profile(value.token()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_families WHERE security_revoked_at IS NULL", Integer.class)).isZero();
        profile(loginService.login("race@example.com", "password123")).andExpect(status().isOk());
    }

    @Test
    void v3MigrationPreservesDataButInvalidatesPreSidFamilies() throws Exception {
        String schema = "member_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("MEMBER_TEST_DB_URL"), user = System.getenv("MEMBER_TEST_DB_USER"), password = System.getenv("MEMBER_TEST_DB_PASSWORD");
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).target("2").load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.executeUpdate("INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at) VALUES ('11111111-1111-1111-1111-111111111111','old@example.com','hash','old','USER',now(),now())");
                statement.executeUpdate("INSERT INTO refresh_families(id,member_id,created_at,expires_at) VALUES ('22222222-2222-2222-2222-222222222222','11111111-1111-1111-1111-111111111111',now(),now()+interval '30 days')");
                statement.executeUpdate("INSERT INTO refresh_tokens(token_hash,family_id,created_at) VALUES ('" + "a".repeat(64) + "','22222222-2222-2222-2222-222222222222',now())");
            }
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema).load().migrate();
            try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
                connection.setSchema(schema);
                try (var rows = statement.executeQuery("SELECT (SELECT count(*) FROM members),(SELECT count(*) FROM refresh_tokens),revoked_at,security_revoked_at FROM refresh_families")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1); assertThat(rows.getInt(2)).isEqualTo(1);
                    assertThat(rows.getTimestamp(3)).isNotNull(); assertThat(rows.getTimestamp(4)).isNotNull();
                }
            }
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }

    private Attempt attemptRefresh(TokenPair token) {
        try { return new Attempt(sessionService.refresh(token.refreshToken())); }
        catch (BusinessException exception) {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
            return new Attempt(null);
        }
    }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(waitThen(first, ready, start)); var b = pool.submit(waitThen(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
    private <T> Callable<T> waitThen(Callable<T> task, CountDownLatch ready, CountDownLatch start) {
        return () -> { ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out"); return task.call(); };
    }
    private record Attempt(TokenPair token) { }
}
