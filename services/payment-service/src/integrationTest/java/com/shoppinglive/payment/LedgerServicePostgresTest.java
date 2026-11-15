package com.shoppinglive.payment;

import static com.shoppinglive.payment.ledger.entity.LedgerAccount.BUYER_DEPOSIT;
import static com.shoppinglive.payment.ledger.entity.LedgerAccount.PG_CUSTODY;
import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.service.Journal.Line;
import com.shoppinglive.payment.ledger.service.LedgerService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** 분개 멱등(ON CONFLICT)과 트랜잭션 참여가 실제 PostgreSQL·V4 제약에서 맞는지 확인한다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class LedgerServicePostgresTest {
    static final String SCHEMA = "payment_ledger_" + UUID.randomUUID().toString().replace("-", "");
    static final int THREADS = 8;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size", () -> THREADS + 2);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("payment.core-service-token", () -> "payment-ledger-postgres-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired LedgerService ledger;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    static List<Line> approval(long amount) {
        return List.of(
                new Line(PG_CUSTODY, amount, 0, null), new Line(BUYER_DEPOSIT, 0, amount, null));
    }

    int journalCount(String refId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal WHERE ref_id = ?", Integer.class, refId);
    }

    List<Long> entryAmounts(String refId) {
        return jdbc.queryForList(
                "SELECT e.debit + e.credit FROM ledger_entry e JOIN ledger_journal j ON j.id = e.journal_id"
                        + " WHERE j.ref_type = 'PAYMENT_APPROVED' AND j.ref_id = ?",
                Long.class,
                refId);
    }

    @Test
    @DisplayName("[LED-03] 같은 사건을 다른 트랜잭션에서 다시 기록하면 0행으로 끝나고 머리 1행·줄 2행만 남는다")
    void duplicateInNewTransaction() {
        String refId = UUID.randomUUID().toString();

        Boolean first =
                tx.execute(
                        s -> ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000)));
        // 두 번째 기록 뒤에도 같은 트랜잭션에서 쿼리가 돈다 = UNIQUE 예외로 트랜잭션이 중단되지 않았다
        Integer afterSecond =
                tx.execute(
                        s -> {
                            assertThat(
                                            ledger.record(
                                                    LedgerRefType.PAYMENT_APPROVED,
                                                    refId,
                                                    approval(50000)))
                                    .isFalse();
                            return journalCount(refId);
                        });

        assertThat(first).isTrue();
        assertThat(afterSecond).isEqualTo(1);
        assertThat(journalCount(refId)).isEqualTo(1);
        assertThat(entryAmounts(refId)).containsExactly(30000L, 30000L);
    }

    @Test
    @DisplayName("[LED-03] 같은 사건을 8개 트랜잭션이 동시에 기록해도 한 번만 true이고 머리 1행·줄 2행이다")
    void concurrentDuplicates() throws Exception {
        String refId = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return tx.execute(
                                            s ->
                                                    ledger.record(
                                                            LedgerRefType.PAYMENT_APPROVED,
                                                            refId,
                                                            approval(30000)));
                                }));
            }
            start.countDown();
            List<Boolean> recorded = new ArrayList<>();
            for (Future<Boolean> f : results) {
                recorded.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(recorded).filteredOn(Boolean::booleanValue).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(journalCount(refId)).isEqualTo(1);
        assertThat(entryAmounts(refId)).hasSize(2);
    }

    @Test
    @DisplayName("[LED-04] 호출한 트랜잭션이 롤백되면 PostgreSQL에도 분개가 남지 않는다")
    void rollsBackWithCaller() {
        String refId = UUID.randomUUID().toString();

        tx.executeWithoutResult(
                s -> {
                    ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000));
                    s.setRollbackOnly();
                });

        assertThat(journalCount(refId)).isZero();
        assertThat(entryAmounts(refId)).isEmpty();
    }
}
