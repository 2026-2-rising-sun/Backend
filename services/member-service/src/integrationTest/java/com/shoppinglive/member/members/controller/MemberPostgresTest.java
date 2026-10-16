package com.shoppinglive.member.members.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.members.service.MemberService;
import com.shoppinglive.member.operations.SellerBootstrapCommand;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 실제 PostgreSQL의 마이그레이션·DB 제약을 검증한다. H2 성공으로 이 검증을 대신하지 않는다. */
@Tag("postgres")
class MemberPostgresTest extends MemberControllerTest {
    @Autowired MemberService service;

    @DisplayName("실제 PostgreSQL 마이그레이션과 DB 제약을 검증한다")
    @Test
    @Tag("migration")
    void validatesRealPostgresMigrationAndDatabaseConstraints() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                .isEqualTo(POSTGRES_SCHEMA);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success",
                                Integer.class))
                .isPositive();
        assertThatThrownBy(() -> insertRaw("UPPER@example.com", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw("user@example.com", "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertRaw("user@example.com", "USER");
        assertThatThrownBy(() -> insertRaw("user@example.com", "USER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("중복 회원가입이 동시에 실행되면 하나만 생성하고 다른 요청은 충돌로 응답한다")
    @Test
    void concurrentDuplicateSignupCreatesOneMemberAndReturnsConflictForLoser() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Integer> signup =
                () -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("실행 시작 대기 시간이 초과되었습니다.");
                    }
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
        jdbc.update(
                "INSERT INTO members(id,email,password_hash,display_name,role,created_at,updated_at) VALUES (?,?,?,?,?,now(),now())",
                UUID.randomUUID(),
                email,
                "not-a-real-password-hash",
                "member",
                role);
    }

    @DisplayName("판매자 초기화는 새 판매자만 생성하고 기존 회원을 승격하지 않는다")
    @Test
    void bootstrapOnlyCreatesNewSellerAndNeverPromotesExistingUser() {
        service.register("user@example.com", "password123", "user");
        var command = new SellerBootstrapCommand();
        assertThatThrownBy(() -> command.execute(bootstrapEnvironment("user@example.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(members.findByEmail("user@example.com").orElseThrow().roles())
                .containsExactly("USER");
        UUID sellerId = command.execute(bootstrapEnvironment("seller@example.com"));
        var seller = members.findById(sellerId).orElseThrow();
        assertThat(seller.roles()).containsExactly("SELLER");
        assertThat(passwords.matches("only-test-password", seller.getPasswordHash())).isTrue();
        assertThatThrownBy(() -> command.execute(bootstrapEnvironment("seller@example.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(members.count()).isEqualTo(2);
    }

    private Map<String, String> bootstrapEnvironment(String email) {
        return Map.of(
                "MEMBER_BOOTSTRAP_DB_URL",
                System.getenv("MEMBER_TEST_DB_URL"),
                "MEMBER_BOOTSTRAP_DB_SCHEMA",
                POSTGRES_SCHEMA,
                "MEMBER_BOOTSTRAP_DB_USER",
                System.getenv("MEMBER_TEST_DB_USER"),
                "MEMBER_BOOTSTRAP_DB_PASSWORD",
                System.getenv("MEMBER_TEST_DB_PASSWORD"),
                "MEMBER_BOOTSTRAP_SELLER_EMAIL",
                email,
                "MEMBER_BOOTSTRAP_SELLER_PASSWORD",
                "only-test-password",
                "MEMBER_BOOTSTRAP_SELLER_DISPLAY_NAME",
                "seller");
    }
}
