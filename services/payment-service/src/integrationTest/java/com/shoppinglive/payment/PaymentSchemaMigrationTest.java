package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 실제 PostgreSQL에 V1–V4를 적용하고 V4 제약이 돈·상태 규칙을 DB에서 막는지 확인한다. */
@Tag("postgres")
@Tag("migration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentSchemaMigrationTest {
    static final String CHECK_VIOLATION = "23514";
    static final String UNIQUE_VIOLATION = "23505";
    static final String PAYMENT_INSERT =
            "INSERT INTO payment(order_ref, buyer_id, amount, refunded_amount, status, expires_at,"
                    + " pg_provider, created_at, updated_at)"
                    + " VALUES (?, '5c2e8f1a-0000-4000-8000-6d4c0b9e7a13', ?, ?, ?,"
                    + " now() + interval '10 minutes', 'TOSS', now(), now())";
    static final String ENTRY_INSERT =
            "INSERT INTO ledger_entry(journal_id, account, debit, credit, seller_id)"
                    + " VALUES (?, ?, ?, ?, ?)";

    final String schema = "payment_schema_" + UUID.randomUUID().toString().replace("-", "");
    Flyway flyway;
    MigrateResult migrated;
    JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        String user = System.getenv("PAYMENT_TEST_POSTGRES_USER");
        String password = System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD");
        flyway =
                Flyway.configure()
                        .locations("classpath:db/payment-migration")
                        .dataSource(new DriverManagerDataSource(url, user, password))
                        .schemas(schema)
                        .defaultSchema(schema)
                        .cleanDisabled(false)
                        .load();
        migrated = flyway.migrate();
        // DriverManagerDataSource는 호출마다 새 커넥션이라 SET search_path 대신 URL로 스키마를 고정한다.
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                                user,
                                password));
    }

    @AfterAll
    void clean() {
        flyway.clean();
    }

    @Test
    @DisplayName("V1–V4가 빈 스키마에 적용되고 옛 결제 테이블 4개는 지워지며 event_outbox는 남는다")
    void migrationsReplaceLegacyTables() {
        assertThat(migrated.success).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(
                        jdbc.queryForList(
                                "SELECT table_name FROM information_schema.tables"
                                        + " WHERE table_schema = ?",
                                String.class,
                                schema))
                .containsExactlyInAnyOrder(
                        "flyway_schema_history",
                        "event_outbox",
                        "payment",
                        "payment_seller_amount",
                        "pg_call",
                        "ledger_journal",
                        "ledger_entry");
    }

    @Test
    @DisplayName("PAY-DATA-01 order_ref는 6–50자만 들어가고 같은 값은 두 번 들어가지 않는다")
    void orderRefLengthAndUniqueness() {
        String six = ref(6);
        jdbc.update(PAYMENT_INSERT, six, 30000L, 0L, "CREATED");
        jdbc.update(PAYMENT_INSERT, ref(50), 30000L, 0L, "CREATED");

        // 51자는 VARCHAR(64) 안이라 CHECK만 막는다.
        rejected(
                CHECK_VIOLATION,
                "payment_order_ref_check",
                PAYMENT_INSERT,
                ref(5),
                30000L,
                0L,
                "CREATED");
        rejected(
                CHECK_VIOLATION,
                "payment_order_ref_check",
                PAYMENT_INSERT,
                ref(51),
                30000L,
                0L,
                "CREATED");
        rejected(
                UNIQUE_VIOLATION,
                "payment_order_ref_key",
                PAYMENT_INSERT,
                six,
                30000L,
                0L,
                "CREATED");
    }

    @Test
    @DisplayName("PAY-DATA-02 refunded_amount는 0 이상 amount 이하만 들어간다")
    void refundedAmountWithinAmount() {
        jdbc.update(PAYMENT_INSERT, ref(20), 30000L, 0L, "APPROVED");
        jdbc.update(PAYMENT_INSERT, ref(20), 30000L, 30000L, "REFUNDED");

        rejected(
                CHECK_VIOLATION,
                "ck_payment_refunded",
                PAYMENT_INSERT,
                ref(20),
                30000L,
                -1L,
                "APPROVED");
        rejected(
                CHECK_VIOLATION,
                "ck_payment_refunded",
                PAYMENT_INSERT,
                ref(20),
                30000L,
                30001L,
                "REFUNDED");
    }

    @Test
    @DisplayName("PAY-DATA-03 status는 7개 상태 중 하나만 들어간다")
    void statusInSevenValues() {
        for (String status :
                new String[] {
                    "CREATED",
                    "APPROVING",
                    "APPROVED",
                    "UNKNOWN",
                    "CANCELED",
                    "PARTIALLY_REFUNDED",
                    "REFUNDED"
                }) {
            jdbc.update(PAYMENT_INSERT, ref(20), 30000L, 0L, status);
        }

        rejected(
                CHECK_VIOLATION,
                "ck_payment_status",
                PAYMENT_INSERT,
                ref(20),
                30000L,
                0L,
                "PENDING");
        rejected(
                CHECK_VIOLATION,
                "ck_payment_status",
                PAYMENT_INSERT,
                ref(20),
                30000L,
                0L,
                "created");
    }

    @Test
    @DisplayName("결제·판매자별 금액은 0보다 크고 수수료율은 0–1이다")
    void amountsArePositive() {
        rejected(
                CHECK_VIOLATION,
                "payment_amount_check",
                PAYMENT_INSERT,
                ref(20),
                0L,
                0L,
                "CREATED");

        String orderRef = ref(20);
        jdbc.update(PAYMENT_INSERT, orderRef, 30000L, 0L, "CREATED");
        long paymentId =
                jdbc.queryForObject(
                        "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
        String sellerInsert =
                "INSERT INTO payment_seller_amount(payment_id, seller_order_number, seller_id, amount,"
                        + " commission_rate) VALUES (?, ?, '7a1c0000-0000-4000-8000-000000000001', ?, ?)";
        jdbc.update(
                sellerInsert,
                paymentId,
                orderRef + "-S1",
                30000L,
                new java.math.BigDecimal("1.0000"));
        rejected(
                CHECK_VIOLATION,
                "payment_seller_amount_amount_check",
                sellerInsert,
                paymentId,
                orderRef + "-S2",
                0L,
                new java.math.BigDecimal("0.1000"));
        rejected(
                CHECK_VIOLATION,
                "payment_seller_amount_commission_rate_check",
                sellerInsert,
                paymentId,
                orderRef + "-S3",
                1000L,
                new java.math.BigDecimal("1.0001"));
    }

    @Test
    @DisplayName("LED-02 분개 줄은 차변·대변 한쪽만 0보다 크고 판매자 미지급금 줄에는 판매자 ID가 있다")
    void entryHasOneSideAndSellerForPayable() {
        long journalId = journal("PAYMENT_APPROVED", ref(10));
        jdbc.update(ENTRY_INSERT, journalId, "PG_CUSTODY", 30000L, 0L, null);
        jdbc.update(ENTRY_INSERT, journalId, "BUYER_DEPOSIT", 0L, 30000L, null);
        jdbc.update(
                ENTRY_INSERT,
                journalId,
                "SELLER_PAYABLE",
                0L,
                27000L,
                "7a1c0000-0000-4000-8000-000000000001");

        rejected(
                CHECK_VIOLATION,
                "ck_ledger_side",
                ENTRY_INSERT,
                journalId,
                "PG_CUSTODY",
                0L,
                0L,
                null);
        rejected(
                CHECK_VIOLATION,
                "ck_ledger_side",
                ENTRY_INSERT,
                journalId,
                "PG_CUSTODY",
                100L,
                100L,
                null);
        rejected(
                CHECK_VIOLATION,
                "ck_ledger_side",
                ENTRY_INSERT,
                journalId,
                "PG_CUSTODY",
                -1L,
                0L,
                null);
        rejected(
                CHECK_VIOLATION,
                "ck_ledger_side",
                ENTRY_INSERT,
                journalId,
                "PG_CUSTODY",
                100L,
                -5L,
                null);
        rejected(
                CHECK_VIOLATION,
                "ck_ledger_seller",
                ENTRY_INSERT,
                journalId,
                "SELLER_PAYABLE",
                0L,
                27000L,
                null);
    }

    @Test
    @DisplayName("LED-03 같은 사건(ref_type, ref_id)의 분개는 1번만 남고 ON CONFLICT DO NOTHING은 0행이다")
    void journalOncePerEvent() {
        String refId = ref(10);
        journal("PAYMENT_APPROVED", refId);
        journal("REFUND_SUCCEEDED", refId);

        rejected(
                UNIQUE_VIOLATION,
                "uq_ledger_journal_ref",
                "INSERT INTO ledger_journal(ref_type, ref_id, created_at) VALUES ('PAYMENT_APPROVED', ?, now())",
                refId);
        assertThat(
                        jdbc.update(
                                "INSERT INTO ledger_journal(ref_type, ref_id, created_at)"
                                        + " VALUES ('PAYMENT_APPROVED', ?, now()) ON CONFLICT DO NOTHING",
                                refId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM ledger_journal WHERE ref_type = 'PAYMENT_APPROVED' AND ref_id = ?",
                                Long.class,
                                refId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("결제창·결제 키·판매자 주문 번호는 중복되지 않고 자식 행은 없는 부모를 가리키지 않는다")
    void keysAndForeignKeys() {
        String orderRef = ref(20);
        jdbc.update(PAYMENT_INSERT, orderRef, 30000L, 0L, "CREATED");
        long paymentId =
                jdbc.queryForObject(
                        "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
        jdbc.update(
                "UPDATE payment SET pg_order_id = ?, pg_payment_key = ? WHERE id = ?",
                orderRef + "-1",
                "key-" + orderRef,
                paymentId);
        String other = ref(20);
        jdbc.update(PAYMENT_INSERT, other, 30000L, 0L, "CREATED");
        String update = "UPDATE payment SET %s = ? WHERE order_ref = ?";
        rejected(
                UNIQUE_VIOLATION,
                "payment_pg_order_id_key",
                update.formatted("pg_order_id"),
                orderRef + "-1",
                other);
        rejected(
                UNIQUE_VIOLATION,
                "payment_pg_payment_key_key",
                update.formatted("pg_payment_key"),
                "key-" + orderRef,
                other);

        String sellerInsert =
                "INSERT INTO payment_seller_amount(payment_id, seller_order_number, seller_id, amount,"
                        + " commission_rate) VALUES (?, ?, '7a1c0000-0000-4000-8000-000000000001', 1000, ?)";
        jdbc.update(sellerInsert, paymentId, orderRef + "-S1", java.math.BigDecimal.ZERO);
        rejected(
                UNIQUE_VIOLATION,
                "payment_seller_amount_seller_order_number_key",
                sellerInsert,
                paymentId,
                orderRef + "-S1",
                java.math.BigDecimal.ZERO);
        rejected(
                CHECK_VIOLATION,
                "payment_seller_amount_commission_rate_check",
                sellerInsert,
                paymentId,
                orderRef + "-S2",
                new java.math.BigDecimal("-0.0001"));
        rejected(
                "23503",
                "payment_seller_amount_payment_id_fkey",
                sellerInsert,
                -1L,
                orderRef + "-S3",
                java.math.BigDecimal.ZERO);
        rejected(
                "23503",
                "pg_call_payment_id_fkey",
                "INSERT INTO pg_call(payment_id, kind, outcome, requested_at) VALUES (?, 'CONFIRM', 'UNKNOWN', now())",
                -1L);
        rejected(
                "23503",
                "ledger_entry_journal_id_fkey",
                ENTRY_INSERT,
                -1L,
                "PG_CUSTODY",
                100L,
                0L,
                null);
    }

    @Test
    @DisplayName("PG 호출 종류·결과, 분개 사건 종류·계정은 정한 값만 들어간다")
    void enumColumnsAreChecked() {
        String orderRef = ref(20);
        jdbc.update(PAYMENT_INSERT, orderRef, 30000L, 0L, "CREATED");
        long paymentId =
                jdbc.queryForObject(
                        "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
        String callInsert =
                "INSERT INTO pg_call(payment_id, kind, outcome, requested_at) VALUES (?, ?, ?, now())";
        jdbc.update(callInsert, paymentId, "CONFIRM", "UNKNOWN");
        rejected(CHECK_VIOLATION, "pg_call_kind_check", callInsert, paymentId, "REFUND", "UNKNOWN");
        rejected(
                CHECK_VIOLATION, "pg_call_outcome_check", callInsert, paymentId, "QUERY", "FAILED");
        rejected(
                CHECK_VIOLATION,
                "ledger_journal_ref_type_check",
                "INSERT INTO ledger_journal(ref_type, ref_id, created_at) VALUES ('ORDER_PLACED', ?, now())",
                ref(10));
        long journalId = journal("PAYMENT_APPROVED", ref(10));
        rejected(
                CHECK_VIOLATION,
                "ledger_entry_account_check",
                ENTRY_INSERT,
                journalId,
                "CASH",
                100L,
                0L,
                null);
    }

    long journal(String refType, String refId) {
        return jdbc.queryForObject(
                "INSERT INTO ledger_journal(ref_type, ref_id, created_at) VALUES (?, ?, now()) RETURNING id",
                Long.class,
                refType,
                refId);
    }

    void rejected(String sqlState, String constraint, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataAccessException.class)
                .satisfies(
                        e -> {
                            var cause = (SQLException) NestedExceptionUtils.getMostSpecificCause(e);
                            assertThat(cause.getSQLState()).isEqualTo(sqlState);
                            assertThat(cause.getMessage()).contains("\"" + constraint + "\"");
                        });
    }

    static String ref(int length) {
        String chars =
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
        return chars.substring(0, length);
    }
}
