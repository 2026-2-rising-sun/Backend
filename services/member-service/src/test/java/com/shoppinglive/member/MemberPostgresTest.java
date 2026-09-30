package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.members.application.MemberService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** postgresTest 작업은 PostgreSQL URL이 없으면 실패한다. H2 성공으로 이 검증을 대신하지 않는다. */
@Tag("postgres")
class MemberPostgresTest extends MemberServiceApplicationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberService service;

    @Test
    void validatesRealPostgresMigrationAndDatabaseConstraints() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(POSTGRES_SCHEMA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isPositive();
        assertThatThrownBy(() -> insertRaw("UPPER@example.com", "USER"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw("user@example.com", "SELLER"))
            .isInstanceOf(DataIntegrityViolationException.class);
        insertRaw("user@example.com", "USER");
        assertThatThrownBy(() -> insertRaw("user@example.com", "USER"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentDuplicateSignupCreatesOneMemberAndReturnsConflictForLoser() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Integer> signup = () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
            try {
                service.register("race@example.com", "password123", "member");
                return 201;
            } catch (BusinessException exception) {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                return 409;
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(signup);
            var second = pool.submit(signup);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(members.count()).isEqualTo(1);
    }

    private void insertRaw(String email, String role) {
        jdbc.update("INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at) VALUES (?,?,?,?,?,now(),now())",
            UUID.randomUUID(), email, "not-a-real-password-hash", "member", role);
    }
}
