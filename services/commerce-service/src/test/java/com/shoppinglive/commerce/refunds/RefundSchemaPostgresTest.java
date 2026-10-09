package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundSchemaPostgresTest {

    private void verify(Consumer<JdbcTemplate> test) {
        String schema = "refund_schema_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var dataSource = new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .cleanDisabled(false).load();
        try {
            flyway.migrate();
            test.accept(new JdbcTemplate(dataSource));
        } finally {
            flyway.clean();
        }
    }

    @Test
    void refundTablesKeepRequestAndOrderLevelAmountsAndSellerOwnershipSnapshot() {
        verify(jdbc -> {
            assertThat(columns(jdbc, "refund_request")).contains(
                "payment_group_id", "member_id", "idempotency_key", "request_fingerprint", "refund_amount", "status", "requested_at", "resolved_at");
            assertThat(columns(jdbc, "refund_target_order")).contains(
                "refund_request_id", "order_id", "product_id_snapshot", "seller_id_snapshot", "refund_amount");
            assertThat(indexes(jdbc, "refund_target_order")).contains(
                "ix_refund_target_order", "ix_refund_target_seller_request");
        });
    }

    @Test
    void refundAmountsAllowZeroAndStatusConstraintContainsTheRefundLifecycle() {
        verify(jdbc -> {
            List<String> constraints = jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid = 'refund_request'::regclass
                """, String.class);

            assertThat(constraints).anySatisfy(value -> assertThat(value).contains("refund_amount >= 0"));
            assertThat(constraints).anySatisfy(value -> assertThat(value)
                .contains("PROCESSING", "UNKNOWN", "SUCCESS", "FAILED"));
        });
    }

    @Test
    void refundRequestsRequireIdempotencyKeyAndFingerprint() {
        verify(jdbc -> {
            List<String> constraints = jdbc.queryForList("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conrelid = 'refund_request'::regclass
                """, String.class);
            assertThat(constraints).anySatisfy(value -> assertThat(value)
                .contains("member_id", "idempotency_key"));
            assertThat(columns(jdbc, "refund_request")).contains("request_fingerprint");
        });
    }

    private static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("""
            SELECT column_name FROM information_schema.columns
             WHERE table_schema = current_schema() AND table_name = ?
            """, String.class, table);
    }

    private static List<String> indexes(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("""
            SELECT indexname FROM pg_indexes
             WHERE schemaname = current_schema() AND tablename = ?
            """, String.class, table);
    }
}
