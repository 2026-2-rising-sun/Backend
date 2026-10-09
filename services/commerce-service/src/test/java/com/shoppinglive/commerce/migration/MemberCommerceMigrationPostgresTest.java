package com.shoppinglive.commerce.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Each test owns a disposable schema; never cleans an existing application schema/database. */
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class MemberCommerceMigrationPostgresTest {
    private final String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
    private final String user = System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres");
    private final String password = System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres");
    private String schema;
    private Connection connection;

    @BeforeEach
    void createSchema() throws Exception {
        schema = "commerce_test_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
    }

    @AfterEach
    void dropOwnedSchema() throws Exception {
        if (connection != null) {
            try { sql("DROP SCHEMA " + schema + " CASCADE"); }
            finally { connection.close(); }
        }
    }

    private Flyway migration(String target) {
        var config = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").cleanDisabled(true);
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void newDatabaseRequiresMemberAndScopesIdempotency() throws Exception {
        migration(null).migrate();
        sale();
        memberOrderWithDiscountSnapshot(1, "11111111-1111-4111-8111-111111111111", "key");
        memberOrderWithDiscountSnapshot(2, "22222222-2222-4222-8222-222222222222", "key");
        assertThatThrownBy(() -> memberOrderWithDiscountSnapshot(3, "11111111-1111-4111-8111-111111111111", "key"))
            .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("uk_orders_member_idempotency");
        assertThatThrownBy(() -> sql("UPDATE orders SET member_id = NULL WHERE id = 1"))
            .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("null");
        assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + schema
            + "' AND table_name = 'orders' AND column_name = 'lookup_password_hash'")).isZero();
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
    }

    @Test
    void legacyDatabaseFailsClosedThenExplicitPurgePreservesMemberAndConsumedStock() throws Exception {
        migration("1").migrate();
        sale();
        guestOrder(1, "PENDING_PAYMENT", 2);
        guestOrder(2, "PAID", 1);
        sql("INSERT INTO payment_attempt (order_id, scenario, status, requested_at, created_at, updated_at) "
            + "VALUES (1,'DELAYED_SUCCESS','PROCESSING',now(),now(),now())");
        migration("2").migrate();
        memberOrder(3, "11111111-1111-4111-8111-111111111111", "member-key");
        sql("UPDATE sales_stock SET available=6, reserved=3 WHERE sales_info_id=1");
        assertThatThrownBy(() -> migration(null).migrate()).isInstanceOf(Exception.class);
        assertThat(count("SELECT count(*) FROM orders WHERE member_id IS NULL")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM payment_attempt")).isEqualTo(1);
        sql(Files.readString(Path.of("ops/purge-development-guest-orders.sql")));
        migration(null).migrate();
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders WHERE id=3")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_attempt")).isZero();
        assertThat(count("SELECT available FROM sales_stock WHERE sales_info_id=1")).isEqualTo(8);
        assertThat(count("SELECT reserved FROM sales_stock WHERE sales_info_id=1")).isEqualTo(1);
    }

    @Test
    void inconsistentGuestReservationsRollbackWithoutDeletingData() throws Exception {
        migration("1").migrate();
        sale();
        guestOrder(1, "PENDING_PAYMENT", 2);
        migration("2").migrate();
        assertThatThrownBy(() -> sql(Files.readString(Path.of("ops/purge-development-guest-orders.sql"))))
            .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Guest reservation mismatch");
        sql("ROLLBACK");
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        assertThat(count("SELECT available FROM sales_stock WHERE sales_info_id=1")).isEqualTo(10);
    }

    @Test
    void paymentGroupMigrationBackfillsLegacyOrderAndLatestAttempt() throws Exception {
        migration("3").migrate();
        sale();
        memberOrder(1, "11111111-1111-4111-8111-111111111111", "legacy-member-order");
        sql("INSERT INTO payment_attempt (id,order_id,scenario,status,requested_at,created_at,updated_at) "
            + "VALUES (1,1,'INSTANT_FAIL','FAILED',now(),now(),now()),(2,1,'DELAYED_SUCCESS','PROCESSING',now(),now(),now())");
        sql("UPDATE orders SET status='PAYMENT_CONFIRMING',expires_at=now()+interval '5 minutes' WHERE id=1");
        migration(null).migrate();
        assertThat(count("SELECT count(*) FROM payment_group WHERE group_number='PG-LEGACY-1' "
            + "AND total_amount=1000 AND status='PAYMENT_CONFIRMING' AND payment_id=2")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders o JOIN payment_group g ON o.payment_group_id=g.id WHERE o.id=1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_attempt p JOIN payment_group g ON p.payment_group_id=g.id WHERE p.id=2")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_attempt WHERE id=1 AND payment_group_id IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM member_purchase_guard")).isEqualTo(1);
    }

    @Test
    void multipleLegacyActiveOrdersFailClosedWithoutChangingOrdersOrReservations() throws Exception {
        migration("3").migrate();
        sale();
        memberOrder(1, "11111111-1111-4111-8111-111111111111", "first");
        memberOrder(2, "11111111-1111-4111-8111-111111111111", "second");
        sql("UPDATE sales_stock SET available=8,reserved=2 WHERE sales_info_id=1");
        assertThatThrownBy(() -> migration(null).migrate()).isInstanceOf(Exception.class)
            .hasMessageContaining("Multiple active orders per member");
        assertThat(count("SELECT count(*) FROM orders WHERE status='PENDING_PAYMENT'")).isEqualTo(2);
        assertThat(count("SELECT available FROM sales_stock WHERE sales_info_id=1")).isEqualTo(8);
        assertThat(count("SELECT reserved FROM sales_stock WHERE sales_info_id=1")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema='" + schema
            + "' AND table_name='payment_group'")).isZero();
    }

    @Test
    void discountSnapshotMigrationBackfillsGrossAndPayableAmountsForLegacyOrdersAndGroups() throws Exception {
        migration("3").migrate();
        sale();
        memberOrder(1, "11111111-1111-4111-8111-111111111111", "snapshot-legacy");

        migration(null).migrate();

        assertThat(count("SELECT total_amount FROM orders WHERE id=1")).isEqualTo(1000);
        assertThat(count("SELECT discount_amount FROM orders WHERE id=1")).isZero();
        assertThat(count("SELECT payable_amount FROM orders WHERE id=1")).isEqualTo(1000);
        assertThat(count("SELECT discount_amount FROM payment_group WHERE group_number='PG-LEGACY-1'")).isZero();
        assertThat(count("SELECT payable_amount FROM payment_group WHERE group_number='PG-LEGACY-1'")).isEqualTo(1000);
        assertThatThrownBy(() -> sql("UPDATE orders SET discount_amount=1001 WHERE id=1"))
            .isInstanceOf(java.sql.SQLException.class);
    }

    private void sale() throws Exception {
        sql("INSERT INTO sales_info (id,product_id,price,status,created_at,updated_at) VALUES (1,1,1000,'ON_SALE',now(),now())");
        sql("INSERT INTO sales_stock (sales_info_id,available,reserved,created_at,updated_at) VALUES (1,10,0,now(),now())");
    }

    private void guestOrder(int id, String status, int quantity) throws Exception {
        sql("INSERT INTO orders (id,order_number,sales_info_id,quantity,unit_price,total_amount,status,buyer_name,buyer_phone,"
            + "lookup_password_hash,product_name_snapshot,created_at,updated_at) VALUES (" + id + ",'guest-" + id
            + "',1," + quantity + ",1000," + quantity * 1000 + ",'" + status + "','buyer','01012345678','legacy-hash','product',now(),now())");
    }

    private void memberOrder(int id, String memberId, String key) throws Exception {
        sql("INSERT INTO orders (id,order_number,sales_info_id,quantity,unit_price,total_amount,status,buyer_name,buyer_phone,"
            + "member_id,idempotency_key,product_name_snapshot,created_at,updated_at) VALUES (" + id + ",'member-" + id
            + "',1,1,1000,1000,'PENDING_PAYMENT','buyer','01012345678','" + memberId + "','" + key + "','product',now(),now())");
    }

    private void memberOrderWithDiscountSnapshot(int id, String memberId, String key) throws Exception {
        sql("INSERT INTO orders (id,order_number,sales_info_id,quantity,unit_price,total_amount,discount_amount,payable_amount,"
            + "status,buyer_name,buyer_phone,member_id,idempotency_key,product_name_snapshot,created_at,updated_at) VALUES ("
            + id + ",'member-" + id + "',1,1,1000,1000,0,1000,'PENDING_PAYMENT','buyer','01012345678','"
            + memberId + "','" + key + "','product',now(),now())");
    }

    private void sql(String query) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(query); }
    }

    private long count(String query) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            result.next(); return result.getLong(1);
        }
    }
}
