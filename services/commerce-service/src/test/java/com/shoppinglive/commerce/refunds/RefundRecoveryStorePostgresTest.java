package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore.Lease;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundRecoveryStorePostgresTest {
    private static final Duration LEASE = Duration.ofMinutes(1);
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RefundRecoveryStore store;
    private Timestamp originalUpdatedAt;

    @BeforeEach
    void migrateLegacyRequestsInOwnedSchema() {
        String schema = "refund_recovery_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("13").load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        jdbc.update("""
            INSERT INTO payment_group(id,group_number,member_id,request_key,fingerprint,total_amount,payable_amount,
                status,expires_at,created_at,updated_at)
            VALUES (1,'lease-group','member','group-key','fingerprint',10000,10000,'PAID',
                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        for (int id = 1; id <= 4; id++) {
            String status = List.of("PROCESSING", "UNKNOWN", "SUCCESS", "FAILED").get(id - 1);
            jdbc.update("""
                INSERT INTO refund_request(id,payment_group_id,member_id,idempotency_key,request_fingerprint,
                    refund_amount,status,requested_at,created_at,updated_at)
                VALUES (?,1,'member',?, ?,2500,?,CURRENT_TIMESTAMP-INTERVAL '10 days',
                    CURRENT_TIMESTAMP-INTERVAL '10 days',CURRENT_TIMESTAMP-INTERVAL '10 days')
                """, id, "key-" + id, "fingerprint-" + id, status);
        }
        originalUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM refund_request WHERE id=2", Timestamp.class);
        flyway.migrate();
        store = new RefundRecoveryStore(jdbc, manager);
    }

    @AfterEach
    void removeOwnedSchema() {
        if (flyway != null) flyway.clean();
    }

    @Test
    void migrationPreservesLegacyBusinessDataAndConstrainsRecoveryMetadata() {
        assertThat(jdbc.queryForObject("SELECT updated_at FROM refund_request WHERE id=2", Timestamp.class))
            .isEqualTo(originalUpdatedAt);
        assertThat(jdbc.queryForList("SELECT status FROM refund_request ORDER BY id", String.class))
            .containsExactly("PROCESSING", "UNKNOWN", "SUCCESS", "FAILED");
        assertThat(jdbc.queryForObject("SELECT execution_started_at=requested_at FROM refund_request WHERE id=2", Boolean.class))
            .isTrue();
        assertThat(jdbc.queryForObject("SELECT execution_started_at IS NULL FROM refund_request WHERE id=1", Boolean.class))
            .isTrue();
        assertThat(jdbc.queryForObject("SELECT SUM(retry_count) FROM refund_request", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE refund_request SET retry_count=4 WHERE id=1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE refund_request SET lease_token='orphan' WHERE id=1"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void simultaneousWorkersAcquireExactlyOneOwner() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var results = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                start.await();
                return new RefundRecoveryStore(jdbc, manager).claim(2, LEASE);
            })).toList();
            start.countDown();
            int owners = 0;
            for (var future : results) if (future.get(15, TimeUnit.SECONDS).isPresent()) owners++;
            assertThat(owners).isEqualTo(1);
        }
        assertThat(store.claim(2, LEASE)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM refund_request WHERE id=2", String.class)).isEqualTo("UNKNOWN");
    }

    @Test
    void lockedRequestDoesNotBlockAnotherDueRequest() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM refund_request WHERE id=1 FOR UPDATE", Long.class);
                locked.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("row lock not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(store.claimDue(2, LEASE)).extracting(Lease::requestId).containsExactly(2L);
            } finally {
                release.countDown();
            }
            holder.get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndOldOwnerCannotRenewOrReschedule() {
        Lease old = store.claim(2, LEASE).orElseThrow();
        expire(2);
        assertThat(store.renew(old, LEASE)).isFalse();
        Lease current = new RefundRecoveryStore(jdbc, manager).claim(2, LEASE).orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(current.idempotencyKey()).isEqualTo("key-2");
        assertThat(current.fingerprint()).isEqualTo("fingerprint-2");
        assertThat(current.refundAmount()).isEqualTo(2500);
        assertThat(current.retryCount()).isZero();
        assertThat(store.release(old, Duration.ZERO)).isFalse();
        assertThat(store.renew(old, LEASE)).isFalse();
        assertThat(store.renew(current, LEASE)).isTrue();
        assertThat(store.release(current, Duration.ZERO)).isTrue();
    }

    @Test
    void futureActionsAndTerminalRequestsAreNotClaimed() {
        Lease owner = store.claim(1, LEASE).orElseThrow();
        assertThat(store.release(owner, Duration.ofMinutes(1))).isTrue();
        assertThat(store.claim(1, LEASE)).isEmpty();
        assertThat(store.claimDue(10, LEASE)).extracting(Lease::requestId).containsExactly(2L);
        assertThat(store.claim(3, LEASE)).isEmpty();
        assertThat(store.claim(4, LEASE)).isEmpty();
        assertThat(store.renew(owner, LEASE)).isFalse();
    }

    @Test
    void committedLeaseSurvivesCallerRollbackAndStoreRecreationAfterSevenDays() {
        var tx = new TransactionTemplate(manager);
        Lease lease = tx.execute(status -> {
            Lease acquired = store.claim(2, LEASE).orElseThrow();
            status.setRollbackOnly();
            return acquired;
        });
        assertThat(jdbc.queryForObject("SELECT lease_token FROM refund_request WHERE id=2", String.class))
            .isEqualTo(lease.token());
        var recreated = new RefundRecoveryStore(jdbc, manager);
        assertThat(recreated.claim(2, LEASE)).isEmpty();
        expire(2);
        assertThat(recreated.claim(2, LEASE).orElseThrow().status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result", Integer.class)).isZero();
    }

    private void expire(long requestId) {
        jdbc.update("UPDATE refund_request SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?", requestId);
    }
}
