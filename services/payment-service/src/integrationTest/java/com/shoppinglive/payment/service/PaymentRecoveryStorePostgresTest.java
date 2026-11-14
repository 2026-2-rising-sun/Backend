package com.shoppinglive.payment.service;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentRecoveryStorePostgresTest {
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private PaymentRecoveryStore store;
    private static final Duration LEASE = Duration.ofMinutes(1);

    @BeforeEach
    void setup() {
        String schema = "payment_recovery_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        System.getenv().getOrDefault("PAYMENT_TEST_POSTGRES_USER", "postgres"),
                        System.getenv().getOrDefault("PAYMENT_TEST_POSTGRES_PASSWORD", "postgres"));
        flyway =
                Flyway.configure()
                        .locations("classpath:db/payment-migration")
                        .dataSource(source)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .cleanDisabled(false)
                        .load();
        Flyway.configure()
                .locations("classpath:db/payment-migration")
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        String[] statuses = {"PROCESSING", "UNKNOWN", "SUCCESS", "PENDING"};
        for (int i = 0; i < 4; i++) {
            insert(i + 1, statuses[i]);
        }
        jdbc.update(
                "UPDATE payment_attempt SET execution_started_at=requested_at WHERE status IN ('PROCESSING','UNKNOWN','SUCCESS')");
        insert(5, "PROCESSING");
        store = new PaymentRecoveryStore(jdbc, manager);
    }

    void insert(long id, String status) {
        jdbc.update(
                "INSERT INTO payment_attempt(id,request_id,payment_id,order_id,reference_number,amount,scenario,status,requested_at,scheduled_resolve_at,created_at,updated_at) VALUES(?,?,?,1,'PG',10000,'INSTANT_SUCCESS',?,CURRENT_TIMESTAMP-INTERVAL '8 days',CURRENT_TIMESTAMP-INTERVAL '1 second',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,
                UUID.randomUUID().toString(),
                id,
                status);
    }

    @AfterEach
    void cleanup() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @DisplayName("마이그레이션은 업무 데이터를 유지하고 복구 메타데이터 제약을 적용한다")
    @Test
    @Tag("migration")
    void migrationKeepsBusinessDataAndLimitsMetadata() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM payment_attempt ORDER BY id", String.class))
                .containsExactly("PROCESSING", "UNKNOWN", "SUCCESS", "PENDING", "PROCESSING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_started_at=requested_at FROM payment_attempt WHERE id=1",
                                Boolean.class))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_started_at IS NULL FROM payment_attempt WHERE id=5",
                                Boolean.class))
                .isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_attempt SET retry_count=4 WHERE id=5"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE payment_attempt SET lease_token='orphan' WHERE id=5"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("작업자가 동시에 선점하면 한 작업자만 소유권을 얻는다")
    @Test
    void simultaneousWorkersHaveOneOwner() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures =
                    java.util.stream.IntStream.range(0, 4)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        return new PaymentRecoveryStore(
                                                                        jdbc, manager)
                                                                .claim(5, LEASE);
                                                    }))
                            .toList();
            start.countDown();
            int owners = 0;
            for (var f : futures) {
                if (f.get(15, TimeUnit.SECONDS).isPresent()) {
                    owners++;
                }
            }
            assertThat(owners).isEqualTo(1);
        }
    }

    @DisplayName("실행 예산은 저장소 재생성과 호출자 롤백 후에도 유지된다")
    @Test
    void budgetSurvivesRecreationAndCallerRollback() {
        var lease = store.claim(5, LEASE).orElseThrow();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            assertThat(store.beginInvocation(lease)).contains(0);
                            tx.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_started_at IS NOT NULL FROM payment_attempt WHERE id=5",
                                Boolean.class))
                .isTrue();
        assertThat(store.unknown(lease, Duration.ZERO)).isTrue();
        for (int i = 1; i <= 3; i++) {
            store = new PaymentRecoveryStore(jdbc, manager);
            var next = store.claim(5, LEASE).orElseThrow();
            assertThat(store.beginInvocation(next)).contains(i);
            assertThat(store.unknown(next, Duration.ZERO)).isTrue();
        }
        var exhausted = new PaymentRecoveryStore(jdbc, manager).claim(5, LEASE).orElseThrow();
        assertThat(store.beginInvocation(exhausted)).isEmpty();
        assertThat(store.unknown(exhausted, Duration.ofMinutes(1))).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT retry_count FROM payment_attempt WHERE id=5",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_attempt WHERE id=5", String.class))
                .isEqualTo("UNKNOWN");
        assertThat(store.claim(5, LEASE)).isEmpty();
    }

    @DisplayName("다시 선점된 작업의 이전 소유자는 완료·재예약할 수 없다")
    @Test
    void expiredOwnerCannotCompleteOrRescheduleAfterReclaim() {
        var old = store.claim(5, LEASE).orElseThrow();
        jdbc.update(
                "UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=5");
        var current = store.claim(5, LEASE).orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(store.complete(old, "SUCCESS")).isFalse();
        assertThat(store.unknown(old, Duration.ZERO)).isFalse();
        assertThat(store.complete(current, "SUCCESS")).isTrue();
        assertThat(store.claim(5, LEASE)).isEmpty();
    }

    @DisplayName("잠긴 후보는 다른 후보를 막지 않고 호환용 PENDING 결제는 제외한다")
    @Test
    void lockedCandidateDoesNotBlockAnotherAndCompatibilityPendingIsExcluded() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM payment_attempt WHERE id=1 FOR UPDATE",
                                                                Long.class);
                                                        locked.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    15, TimeUnit.SECONDS)) {
                                                                throw new AssertionError();
                                                            }
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                    }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(store.claimDue(10, LEASE))
                        .extracting(PaymentRecoveryStore.Lease::id)
                        .containsExactly(2L, 5L);
            } finally {
                release.countDown();
            }
            holder.get(15, TimeUnit.SECONDS);
        }
        assertThat(store.claim(3, LEASE)).isEmpty();
        assertThat(store.claim(4, LEASE)).isEmpty();
    }

    @DisplayName("결과 Outbox 저장이 롤백되면 복구 완료 표시도 함께 롤백된다")
    @Test
    void completionRollsBackTogetherWithBusinessWrite() {
        var lease = store.claim(5, LEASE).orElseThrow();
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            jdbc.update(
                                    "INSERT INTO event_outbox(event_id,event_type,topic,message_key,payload,created_at) VALUES (?,'PaymentExecutionResolved','payment.execution-results.v1','request','{}',CURRENT_TIMESTAMP)",
                                    UUID.randomUUID().toString());
                            assertThat(store.complete(lease, "SUCCESS")).isTrue();
                            tx.setRollbackOnly();
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_attempt WHERE id=5", String.class))
                .isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isZero();
        assertThat(store.owns(lease)).isTrue();
    }

    @Test
    @DisplayName("미호출 종료도 만료된 lease나 교체된 토큰으로는 적용하지 않는다")
    void closedUnstartedAttemptStillRequiresLiveLease() {
        var lease = store.claim(5, LEASE).orElseThrow();
        jdbc.update(
                "UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=5");
        assertThat(store.rejectUnstarted(lease)).isFalse();
        var replacement = store.claim(5, LEASE).orElseThrow();
        assertThat(store.rejectUnstarted(lease)).isFalse();
        assertThat(store.rejectUnstarted(replacement)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_attempt WHERE id=5", String.class))
                .isEqualTo("FAILED");
    }
}
