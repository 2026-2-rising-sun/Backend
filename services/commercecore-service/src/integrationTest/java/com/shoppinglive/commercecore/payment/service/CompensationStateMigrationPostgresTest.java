package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class CompensationStateMigrationPostgresTest {
    @Test
    @DisplayName("기적용V115의 미완료 보상만 V116대기로 이행하고 원문·예약·종료주문·기존checksum을 보존한다")
    void migratesExistingIntentWithoutReleasingResourcesOrRevivingClosedOrder() {
        String schema = "compensation_state_" + UUID.randomUUID().toString().replace("-", "");
        var ds =
                new DriverManagerDataSource(
                        System.getenv("COMMERCECORE_TEST_POSTGRES_URL")
                                + "?currentSchema="
                                + schema,
                        System.getenv("COMMERCECORE_TEST_POSTGRES_USER"),
                        System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        var old =
                Flyway.configure()
                        .dataSource(ds)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("115")
                        .load();
        var all =
                Flyway.configure()
                        .dataSource(ds)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .cleanDisabled(false)
                        .load();
        try {
            old.migrate();
            var jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT current_schema()", String.class))
                    .isEqualTo(schema);
            int checksum =
                    jdbc.queryForObject(
                            "SELECT checksum FROM flyway_schema_history WHERE version='115'",
                            Integer.class);
            jdbc.update(
                    "INSERT INTO product(id,name,description,main_image_id,seller_id,category,sale_enabled,version,created_at,updated_at) VALUES(901,'상품','설명',1,'seller','FASHION',TRUE,0,now(),now())");
            jdbc.update(
                    "INSERT INTO sku(id,product_id,price,available,reserved,sale_status,snapshot_version,product_name_snapshot,created_at,updated_at) VALUES(902,901,4000,17,3,'ON_SALE',1,'상품',now(),now())");
            for (int i = 1; i <= 3; i++) {
                String status = i == 2 ? "EXPIRED" : "PAYMENT_CONFIRMING";
                jdbc.update(
                        "INSERT INTO payment_group(id,group_number,member_id,request_key,fingerprint,total_amount,discount_amount,payable_amount,status,expires_at,processing_expires_at,policy_version,version,created_at,updated_at) VALUES(?,?,?,?,?,12000,0,12000,?,now(),now(),1,7,now(),now())",
                        i,
                        "number-" + i,
                        "member-" + i,
                        "key",
                        "hash",
                        status);
                jdbc.update(
                        "INSERT INTO orders(id,order_number,member_id,payment_group_id,sku_id,quantity,unit_price,total_amount,discount_amount,payable_amount,status,buyer_name,buyer_phone,product_name_snapshot,version,created_at,updated_at) VALUES(?,?,?,?,902,3,4000,12000,0,12000,?,'구매자','010','상품',5,now(),now())",
                        i,
                        "item-" + i,
                        "member-" + i,
                        i,
                        status);
                if (i < 3)
                    jdbc.update(
                            "INSERT INTO core_refund_intent(refund_request_id,payment_group_id,purpose,request_payload,status,result_version) VALUES(?,?,'SYSTEM_COMPENSATION',?,'FAILED',2)",
                            "request-" + i,
                            i,
                            "{\"original\":" + i + "}");
            }
            all.migrate();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM payment_group WHERE id=1", String.class))
                    .isEqualTo("COMPENSATION_PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=1", String.class))
                    .isEqualTo("COMPENSATION_PENDING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM payment_group WHERE id=2", String.class))
                    .isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=2", String.class))
                    .isEqualTo("EXPIRED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM payment_group WHERE id=3", String.class))
                    .isEqualTo("PAYMENT_CONFIRMING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT request_payload FROM core_refund_intent WHERE refund_request_id='request-1'",
                                    String.class))
                    .isEqualTo("{\"original\":1}");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT result_version FROM core_refund_intent WHERE refund_request_id='request-1'",
                                    Long.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT available FROM sku WHERE id=902", Integer.class))
                    .isEqualTo(17);
            assertThat(jdbc.queryForObject("SELECT reserved FROM sku WHERE id=902", Integer.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT checksum FROM flyway_schema_history WHERE version='115'",
                                    Integer.class))
                    .isEqualTo(checksum);
            // V117: 보상 대기 주문은 회원의 새 주문을 막지 않고, 결제 확인 중 주문은 여전히 1건으로 막는다.
            jdbc.update(
                    "INSERT INTO payment_group(id,group_number,member_id,request_key,fingerprint,total_amount,discount_amount,payable_amount,status,expires_at,version,created_at,updated_at) VALUES(4,'other','member-1','other','hash',12000,0,12000,'PENDING_PAYMENT',now(),0,now(),now())");
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "INSERT INTO payment_group(id,group_number,member_id,request_key,fingerprint,total_amount,discount_amount,payable_amount,status,expires_at,version,created_at,updated_at) VALUES(5,'other-2','member-3','other-2','hash',12000,0,12000,'PENDING_PAYMENT',now(),0,now(),now())"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_payment_group_active_member");
            for (String table : java.util.List.of("orders", "payment_group"))
                assertThat(
                                jdbc.queryForList(
                                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=?::regclass",
                                        String.class,
                                        table))
                        .anySatisfy(
                                value ->
                                        assertThat(value)
                                                .contains(
                                                        "COMPENSATION_PENDING", "CANCEL_PENDING"));
        } finally {
            all.clean();
        }
    }
}
