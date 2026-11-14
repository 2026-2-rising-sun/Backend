package com.shoppinglive.commercecore.order.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class OrderSheetMigrationPostgresTest {
    private final String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
    private final String user =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_USER", "postgres");
    private final String password =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_PASSWORD", "postgres");
    private String schema;
    private Connection connection;

    @BeforeEach
    void seedLegacyPurchase() throws Exception {
        schema = "order_migration_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        migration("100").migrate();
        sql(
                "INSERT INTO sales_info(id,product_id,price,status,created_at,updated_at) VALUES(41,501,9000,'ON_SALE',now(),now())");
        sql(
                "INSERT INTO sales_stock(sales_info_id,available,reserved,created_at,updated_at) VALUES(41,8,2,now(),now())");
        sql(
                "INSERT INTO cart_item(id,member_id,product_id,quantity,version,created_at,updated_at) VALUES(12,'member-a',501,2,7,now(),now())");
        sql(
                "INSERT INTO payment_group(id,group_number,member_id,request_key,fingerprint,total_amount,discount_amount,payable_amount,status,expires_at,version,created_at,updated_at) VALUES(11,'old-number','member-a','old-key','old',8000,2000,6000,'PENDING_PAYMENT','2026-11-13T00:15:00Z',3,'2026-11-13T00:00:00Z','2026-11-13T00:00:00Z')");
        sql(
                "INSERT INTO orders(id,order_number,sales_info_id,quantity,unit_price,total_amount,discount_amount,payable_amount,status,buyer_name,buyer_phone,member_id,product_name_snapshot,payment_group_id,source_cart_item_id,source_cart_item_version,expires_at,version,created_at,updated_at) VALUES(31,'old-item',41,2,4000,8000,2000,6000,'PENDING_PAYMENT','회원','010','member-a','옛 이름',11,12,7,'2026-11-13T00:15:00Z',5,'2026-11-13T00:00:00Z','2026-11-13T00:00:00Z')");
        sql(
                "INSERT INTO refund_request(id,payment_group_id,member_id,idempotency_key,request_fingerprint,refund_amount,status,requested_at,created_at,updated_at) VALUES(71,11,'member-a','refund-old','old',6000,'FAILED',now(),now(),now())");
        sql(
                "INSERT INTO refund_target_order(refund_request_id,order_id,product_id_snapshot,refund_amount,created_at) VALUES(71,31,501,6000,now())");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (connection != null) {
            try {
                sql("DROP SCHEMA " + schema + " CASCADE");
            } finally {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("V104는 기존 PK·15분기한·과거가격·Cart출처·예약수량을 보존한다")
    void preservesLegacyPurchaseAndQuantity() throws Exception {
        migration("104").migrate();
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM payment_group WHERE id=11 AND group_number='old-number' AND policy_version=0 AND status='PENDING_PAYMENT' AND total_amount=8000 AND discount_amount=2000 AND payable_amount=6000 AND expires_at-created_at=INTERVAL '15 minutes' AND version=3"))
                .isEqualTo(1);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM orders WHERE id=31 AND sales_info_id=41 AND sku_id=41 AND product_id_snapshot=501 AND unit_price=4000 AND product_name_snapshot='옛 이름' AND source_cart_item_id=12 AND source_cart_item_version=7 AND version=5 AND selected_options_snapshot IS NULL"))
                .isEqualTo(1);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM sku WHERE id=41 AND product_id=501 AND price=9000 AND available=8 AND reserved=2 AND snapshot_version>0"))
                .isEqualTo(1);
        assertThat(number("SELECT quantity FROM refund_target_order WHERE refund_request_id=71"))
                .isEqualTo(2);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM refund_request WHERE id=71 AND purpose='USER' AND status='FAILED' AND refund_amount=6000"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM cart_item WHERE id=12 AND sku_id=41 AND version=7"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("이행 전 활성 주문의 예약 수량은 SKU에서 한 번 소진하거나 해제할 수 있다")
    void migratedReservationSupportsOriginalCompletion() throws Exception {
        migration("104").migrate();
        sql("UPDATE sku SET available=available+2,reserved=reserved-2 WHERE id=41 AND reserved>=2");
        assertThat(number("SELECT available FROM sku WHERE id=41")).isEqualTo(10);
        assertThat(number("SELECT reserved FROM sku WHERE id=41")).isZero();
        assertThatThrownBy(() -> sql("UPDATE sku SET reserved=reserved-2 WHERE id=41"))
                .isInstanceOf(SQLException.class);
        assertThat(number("SELECT COUNT(*) FROM orders WHERE id=31 AND unit_price=4000"))
                .isEqualTo(1);
    }

    private Flyway migration(String target) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(true)
                .load();
    }

    private void sql(String s) throws SQLException {
        try (var q = connection.createStatement()) {
            q.execute(s);
        }
    }

    private long number(String s) throws SQLException {
        try (var q = connection.createStatement();
                var r = q.executeQuery(s)) {
            r.next();
            return r.getLong(1);
        }
    }
}
