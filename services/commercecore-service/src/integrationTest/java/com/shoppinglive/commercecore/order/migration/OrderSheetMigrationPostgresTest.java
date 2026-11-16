package com.shoppinglive.commercecore.order.migration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class OrderSheetMigrationPostgresTest {
    private final String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
    private final String user =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_USER", "postgres");
    private final String password =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_PASSWORD", "postgres");
    @TempDir Path archivedDirectory;
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
                                "SELECT COUNT(*) FROM refund_request WHERE id=71 AND status='FAILED' AND refund_amount=6000"))
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

    @Test
    @DisplayName("V105는 기존 정책0·1·2 확인중 주문의 기한을 임의로 계산하거나 상태를 바꾸지 않는다")
    void preservesExistingProcessingPoliciesAndDeadline() throws Exception {
        migration("104").migrate();
        sql("UPDATE payment_group SET status='PAYMENT_CONFIRMING' WHERE id=11");
        for (int policy : new int[] {1, 2}) {
            sql(
                    "INSERT INTO payment_group(group_number,member_id,request_key,fingerprint,total_amount,discount_amount,payable_amount,status,expires_at,version,created_at,updated_at,policy_version) "
                            + "VALUES ('old-policy-"
                            + policy
                            + "','member-"
                            + policy
                            + "','old','old',8000,2000,6000,'PAYMENT_CONFIRMING','2026-11-13T00:15:00Z',3,'2026-11-13T00:00:00Z','2026-11-13T00:00:00Z',"
                            + policy
                            + ")");
        }
        migration("105").migrate();
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM payment_group WHERE status='PAYMENT_CONFIRMING' AND processing_expires_at IS NULL AND expires_at-created_at=INTERVAL '15 minutes' AND version=3"))
                .isEqualTo(3);
        assertThat(number("SELECT COUNT(*) FROM late_approval_refund")).isZero();
        migration("105").validate();
    }

    @Test
    @DisplayName("V106은 기존 번호·금액·기한·전달 이력을 보존하고 새 연동 표만 추가한다")
    void relaySchemaDoesNotRewriteExistingOrders() throws Exception {
        migration("105").migrate();
        sql(
                "INSERT INTO payment_delivery_request(request_id,kind,order_id,payment_group_id,reference_number,amount,request_key,payment_id,command,status,requested_at,next_action_at) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','PAYMENT',31,11,'old-number',6000,'old-payment',81,'old-command','UNKNOWN',now(),now())");
        migration("106").migrate();
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM payment_group WHERE id=11 AND group_number='old-number' AND expires_at-created_at=INTERVAL '15 minutes' AND payable_amount=6000 AND processing_expires_at IS NULL"))
                .isEqualTo(1);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM payment_delivery_request WHERE payment_id=81 AND kind='PAYMENT' AND status='UNKNOWN' AND command='old-command'"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM seller_order")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_payment_relay")).isZero();
        assertThat(number("SELECT COUNT(*) FROM payment_relay_refund")).isZero();
        sql(
                "INSERT INTO payment_delivery_request(request_id,kind,order_id,payment_group_id,reference_number,amount,request_key,command,status,requested_at,next_action_at) VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','PREPARE',31,11,'old-number',6000,'prepare','request','PROCESSING',now(),now())");
        assertThatThrownBy(
                        () ->
                                sql(
                                        "INSERT INTO payment_delivery_request(request_id,kind,order_id,payment_group_id,reference_number,amount,request_key,command,status,requested_at,next_action_at) VALUES('cccccccc-cccc-4ccc-8ccc-cccccccccccc','PREPARE',31,11,'old-number',6000,'other','request','PROCESSING',now(),now())"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("보존 V104 체크섬은 자동 이행을 차단하고 전용 스냅샷의 제한적 repair 뒤 원문과 null 기한을 보존한다")
    void archivedSchemaRequiresExplicitMetadataReconciliation() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath:db/migration/*.sql")) {
            try (var input = resource.getInputStream()) {
                Files.copy(input, archivedDirectory.resolve(resource.getFilename()));
            }
        }
        try (var input =
                getClass()
                        .getResourceAsStream(
                                "/order-migration/V104__order_sheets_and_closure.sql")) {
            Files.copy(
                    input,
                    archivedDirectory.resolve("V104__order_sheets_and_closure.sql"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        var archived =
                Flyway.configure()
                        .dataSource(url, user, password)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("filesystem:" + archivedDirectory)
                        .target("104")
                        .cleanDisabled(true)
                        .load();
        archived.migrate();
        assertThat(number("SELECT checksum FROM flyway_schema_history WHERE version='104'"))
                .isEqualTo(1274506072L);
        sql(
                "UPDATE payment_group SET status='PAYMENT_CONFIRMING', policy_version=1,payment_id=81 WHERE id=11");
        sql("UPDATE orders SET status='PAYMENT_CONFIRMING' WHERE id=31");
        sql(
                "UPDATE refund_request SET purpose='COMPENSATION',order_closure_request_id='dddddddd-dddd-4ddd-8ddd-dddddddddddd' WHERE id=71");
        sql(
                "INSERT INTO order_payment_closure(closure_request_id,payment_request_id,payment_id,order_id,payment_group_id,reference_number,amount,reason,closed_at,acknowledged,next_action_at) VALUES('dddddddd-dddd-4ddd-8ddd-dddddddddddd','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',81,31,11,'old-number',6000,'EXPIRED',now(),FALSE,now())");
        sql(
                "INSERT INTO payment_delivery_request(request_id,kind,order_id,payment_group_id,reference_number,amount,request_key,payment_id,command,status,requested_at,next_action_at) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','PAYMENT',31,11,'old-number',6000,'old-payment',81,'{\"orderClosureRequestId\":\"dddddddd-dddd-4ddd-8ddd-dddddddddddd\",\"paymentId\":81}','UNKNOWN',now(),now())");
        String before = financialSnapshot();
        assertThatThrownBy(() -> migration("106").migrate())
                .hasMessageContaining("checksum mismatch")
                .hasMessageContaining("104");
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                                        + schema
                                        + "' AND column_name='processing_expires_at'"))
                .isZero();

        // 운영 자동 복구가 아니다. 알려진 보존본의 104만 다른 전용 스냅샷에서 기술적 호환성을 검증한다.
        var validation = migration("104").validateWithResult();
        assertThat(validation.invalidMigrations).hasSize(1);
        assertThat(validation.invalidMigrations.getFirst().version).isEqualTo("104");
        assertThat(
                        Arrays.stream(archived.info().applied())
                                .filter(m -> !m.getVersion().toString().equals("104"))
                                .allMatch(
                                        m ->
                                                Arrays.stream(migration("106").info().all())
                                                        .anyMatch(
                                                                current ->
                                                                        current.getVersion()
                                                                                        .equals(
                                                                                                m
                                                                                                        .getVersion())
                                                                                && java.util.Objects
                                                                                        .equals(
                                                                                                current
                                                                                                        .getChecksum(),
                                                                                                m
                                                                                                        .getChecksum()))))
                .isTrue();
        migration("104").repair();
        migration("106").migrate();
        migration("106").validate();
        assertThat(number("SELECT checksum FROM flyway_schema_history WHERE version='104'"))
                .isNotEqualTo(1274506072L);
        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM payment_group WHERE id=11 AND processing_expires_at IS NULL AND status='PAYMENT_CONFIRMING' AND policy_version=1 AND expires_at-created_at=INTERVAL '15 minutes'"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_payment_relay")).isZero();
        assertThat(number("SELECT COUNT(*) FROM payment_relay_refund")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_payment_closure WHERE acknowledged=FALSE"))
                .isEqualTo(1);
        // DDL 호환 통과와 금전 처리 재개는 다르다. 미확정 원문·종료 기록은 이행 승인 대상에 남는다.
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM payment_delivery_request WHERE status='UNKNOWN' AND kind='PAYMENT'"))
                .isEqualTo(1);
    }

    private String financialSnapshot() throws SQLException {
        var result = new StringBuilder();
        for (String table :
                new String[] {
                    "payment_group",
                    "orders",
                    "refund_request",
                    "refund_target_order",
                    "payment_delivery_request",
                    "order_payment_closure",
                    "sku",
                    "cart_item"
                }) {
            try (var statement = connection.createStatement();
                    var rows =
                            statement.executeQuery(
                                    "SELECT COALESCE(jsonb_agg(jsonb_strip_nulls(to_jsonb(r)) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM "
                                            + table
                                            + " r")) {
                rows.next();
                result.append(table).append(':').append(rows.getString(1)).append('\n');
            }
        }
        return result.toString();
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
