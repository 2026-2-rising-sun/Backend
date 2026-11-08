package com.shoppinglive.commercecore.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 각 테스트는 폐기 가능한 전용 스키마를 사용하며 기존 애플리케이션의 스키마나 DB를 정리하지 않는다. */
@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class MemberCommerceMigrationPostgresTest {
    private final String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
    private final String user =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_USER", "postgres");
    private final String password =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_PASSWORD", "postgres");
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
            try {
                sql("DROP SCHEMA " + schema + " CASCADE");
            } finally {
                connection.close();
            }
        }
    }

    private Flyway migration(String target) {
        var config =
                Flyway.configure()
                        .dataSource(url, user, password)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                        .cleanDisabled(true);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    @DisplayName("새 DB는 회원을 필수로 요구하고 회원별로 멱등 키를 구분한다")
    @Test
    void newDatabaseRequiresMemberAndScopesIdempotency() throws Exception {
        migration(null).migrate();
        sale();
        memberOrderWithDiscountSnapshot(1, "11111111-1111-4111-8111-111111111111", "key");
        memberOrderWithDiscountSnapshot(2, "22222222-2222-4222-8222-222222222222", "key");
        assertThatThrownBy(
                        () ->
                                memberOrderWithDiscountSnapshot(
                                        3, "11111111-1111-4111-8111-111111111111", "key"))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("uk_orders_member_idempotency");
        assertThatThrownBy(() -> sql("UPDATE orders SET member_id = NULL WHERE id = 1"))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("null");
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = '"
                                        + schema
                                        + "' AND table_name = 'orders' AND column_name = 'lookup_password_hash'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
    }

    @Test
    @DisplayName("최신 주문 스키마는 유니크·외래키·필수값·수량 제약을 구별 가능한 오류로 보고한다")
    void reportsDistinctOrderConstraintsAfterMigration() throws Exception {
        migration(null).migrate();
        sale();
        memberOrderWithDiscountSnapshot(1, "11111111-1111-4111-8111-111111111111", "key");
        memberOrderWithDiscountSnapshot(2, "22222222-2222-4222-8222-222222222222", "other");

        assertOrderViolation(
                "UPDATE orders SET order_number='member-1' WHERE id=2",
                "23505",
                "uk_orders_order_number");
        assertOrderViolation(
                "UPDATE orders SET member_id='11111111-1111-4111-8111-111111111111',idempotency_key='key' WHERE id=2",
                "23505",
                "uk_orders_member_idempotency");
        assertOrderViolation(
                "UPDATE orders SET sales_info_id=999999 WHERE id=2",
                "23503",
                "fk_orders_sales_info");
        assertOrderViolation("UPDATE orders SET member_id=NULL WHERE id=2", "23502", null);
        assertOrderViolation(
                "UPDATE orders SET quantity=0 WHERE id=2", "23514", "ck_orders_quantity_positive");
        assertThat(count("SELECT count(*) FROM orders")).isEqualTo(2);
        assertThat(
                        count(
                                "SELECT count(*) FROM orders WHERE quantity=1 AND sales_info_id=1 AND member_id IS NOT NULL"))
                .isEqualTo(2);
        assertThat(count("SELECT available FROM sales_stock WHERE sales_info_id=1")).isEqualTo(10);
        assertThat(count("SELECT reserved FROM sales_stock WHERE sales_info_id=1")).isZero();
    }

    private void assertOrderViolation(String statement, String state, String constraint) {
        assertThatThrownBy(() -> sql(statement))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> {
                            assertThat(failure.getSQLState()).isEqualTo(state);
                            if (constraint != null) {
                                assertThat(
                                                new PostgreSQLDialect()
                                                        .getViolatedConstraintNameExtractor()
                                                        .extractConstraintName(failure))
                                        .isEqualTo(constraint);
                            }
                        });
    }

    @DisplayName("결제 묶음은 주문을 연결하고 Payment 원본 대신 전달 요청 참조를 보관한다")
    @Test
    void paymentGroupLinksOrdersWithoutPaymentExecutionTables() throws Exception {
        migration("3").migrate();
        sale();
        memberOrder(1, "11111111-1111-4111-8111-111111111111", "legacy-member-order");
        sql(
                "UPDATE orders SET status='PAYMENT_CONFIRMING',expires_at=now()+interval '5 minutes' WHERE id=1");
        migration(null).migrate();
        assertThat(
                        count(
                                "SELECT count(*) FROM payment_group WHERE group_number='PG-LEGACY-1' AND total_amount=1000 AND status='PAYMENT_CONFIRMING' AND payment_id IS NULL"))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM orders o JOIN payment_group g ON o.payment_group_id=g.id WHERE o.id=1"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM member_purchase_guard")).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name IN ('payment_attempt','mock_gateway_result','refund_execution','mock_refund_result')"))
                .isZero();
    }

    @DisplayName("기존 활성 주문이 여러 개면 주문·예약을 변경하지 않고 마이그레이션을 중단한다")
    @Test
    void multipleLegacyActiveOrdersFailClosedWithoutChangingOrdersOrReservations()
            throws Exception {
        migration("3").migrate();
        sale();
        memberOrder(1, "11111111-1111-4111-8111-111111111111", "first");
        memberOrder(2, "11111111-1111-4111-8111-111111111111", "second");
        sql("UPDATE sales_stock SET available=8,reserved=2 WHERE sales_info_id=1");
        assertThatThrownBy(() -> migration(null).migrate())
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Multiple active orders per member");
        assertThat(count("SELECT count(*) FROM orders WHERE status='PENDING_PAYMENT'"))
                .isEqualTo(2);
        assertThat(count("SELECT available FROM sales_stock WHERE sales_info_id=1")).isEqualTo(8);
        assertThat(count("SELECT reserved FROM sales_stock WHERE sales_info_id=1")).isEqualTo(2);
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema='"
                                        + schema
                                        + "' AND table_name='payment_group'"))
                .isZero();
    }

    @DisplayName("할인 스냅샷 마이그레이션은 기존 주문·묶음의 원금과 실부담액을 채운다")
    @Test
    void discountSnapshotMigrationBackfillsGrossAndPayableAmountsForLegacyOrdersAndGroups()
            throws Exception {
        migration("3").migrate();
        sale();
        memberOrder(1, "11111111-1111-4111-8111-111111111111", "snapshot-legacy");

        migration(null).migrate();

        assertThat(count("SELECT total_amount FROM orders WHERE id=1")).isEqualTo(1000);
        assertThat(count("SELECT discount_amount FROM orders WHERE id=1")).isZero();
        assertThat(count("SELECT payable_amount FROM orders WHERE id=1")).isEqualTo(1000);
        assertThat(
                        count(
                                "SELECT discount_amount FROM payment_group WHERE group_number='PG-LEGACY-1'"))
                .isZero();
        assertThat(
                        count(
                                "SELECT payable_amount FROM payment_group WHERE group_number='PG-LEGACY-1'"))
                .isEqualTo(1000);
        assertThatThrownBy(() -> sql("UPDATE orders SET discount_amount=1001 WHERE id=1"))
                .isInstanceOf(java.sql.SQLException.class);
    }

    @DisplayName("영속 전달 원문은 요청 UUID와 결제 참조 중복을 막고 주문 FK를 유지한다")
    @Test
    void deliveryRequestUniquenessAndLocalOrderForeignKey() throws Exception {
        migration(null).migrate();
        sale();
        memberOrderWithDiscountSnapshot(
                1, "11111111-1111-4111-8111-111111111111", "delivery-order");
        String request = UUID.randomUUID().toString();
        sql(
                "INSERT INTO payment_delivery_request(request_id,kind,order_id,reference_number,amount,request_key,payment_id,command,status,requested_at,next_action_at) VALUES ('"
                        + request
                        + "','PAYMENT',1,'OD',1000,'key',1,'{}','UNKNOWN',now(),now())");
        assertThatThrownBy(
                        () ->
                                sql(
                                        "INSERT INTO payment_delivery_request(request_id,kind,order_id,reference_number,amount,request_key,payment_id,command,status,requested_at,next_action_at) VALUES ('"
                                                + request
                                                + "','PAYMENT',1,'OD',1000,'key2',2,'{}','PROCESSING',now(),now())"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                sql(
                                        "INSERT INTO payment_delivery_request(request_id,kind,order_id,reference_number,amount,request_key,payment_id,command,status,requested_at,next_action_at) VALUES ('"
                                                + UUID.randomUUID()
                                                + "','PAYMENT',1,'OD',1000,'key3',1,'{}','PROCESSING',now(),now())"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                        () ->
                                sql(
                                        "UPDATE payment_delivery_request SET order_id=999 WHERE request_id='"
                                                + request
                                                + "'"))
                .isInstanceOf(SQLException.class);
        assertThat(
                        count(
                                "SELECT count(*) FROM payment_delivery_request WHERE status='UNKNOWN' AND amount=1000"))
                .isEqualTo(1);
    }

    @DisplayName("Core 환불은 정책·대상과 결과 사본을 보관하고 실행권은 보관하지 않는다")
    @Test
    void refundProjectionHasNoExecutionLeaseOrProviderResult() throws Exception {
        migration(null).migrate();
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='refund_request' AND column_name IN ('lease_token','lease_until','execution_started_at')"))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name IN ('refund_request','refund_target_order','payment_delivery_request','event_outbox')"))
                .isEqualTo(4);
    }

    private void sale() throws Exception {
        sql(
                "INSERT INTO sales_info (id,product_id,price,status,created_at,updated_at) VALUES (1,1,1000,'ON_SALE',now(),now())");
        sql(
                "INSERT INTO sales_stock (sales_info_id,available,reserved,created_at,updated_at) VALUES (1,10,0,now(),now())");
    }

    private void memberOrder(int id, String memberId, String key) throws Exception {
        sql(
                "INSERT INTO orders (id,order_number,sales_info_id,quantity,unit_price,total_amount,status,buyer_name,buyer_phone,"
                        + "member_id,idempotency_key,product_name_snapshot,created_at,updated_at) VALUES ("
                        + id
                        + ",'member-"
                        + id
                        + "',1,1,1000,1000,'PENDING_PAYMENT','buyer','01012345678','"
                        + memberId
                        + "','"
                        + key
                        + "','product',now(),now())");
    }

    private void memberOrderWithDiscountSnapshot(int id, String memberId, String key)
            throws Exception {
        sql(
                "INSERT INTO orders (id,order_number,sales_info_id,quantity,unit_price,total_amount,discount_amount,payable_amount,"
                        + "status,buyer_name,buyer_phone,member_id,idempotency_key,product_name_snapshot,created_at,updated_at) VALUES ("
                        + id
                        + ",'member-"
                        + id
                        + "',1,1,1000,1000,0,1000,'PENDING_PAYMENT','buyer','01012345678','"
                        + memberId
                        + "','"
                        + key
                        + "','product',now(),now())");
    }

    private void sql(String query) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private long count(String query) throws Exception {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }
}
