package com.shoppinglive.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 실제 PostgreSQL에 Flyway를 적용하고 JPA 매핑 및 알림 테이블 생성을 확인한다. */
@SpringBootTest
@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(
        named = "NOTIFICATION_TEST_POSTGRES_URL",
        matches = "jdbc:postgresql:.*")
class NotificationPostgresMigrationTest extends NotificationSecuritySupport {
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("기존 V7 DB를 올려도 V6 이력과 판매 상태가 유지되고 이전 코드의 읽기와 쓰기가 가능하다")
    void upgradesExistingDatabaseWithoutBreakingLegacySalesWrites() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("NOTIFICATION_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("NOTIFICATION_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("NOTIFICATION_TEST_DB_PASSWORD", "test");
        var legacy =
                Flyway.configure()
                        .dataSource(url, user, password)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("7")
                        .cleanDisabled(false)
                        .load();
        try {
            legacy.migrate();
            var source =
                    new DriverManagerDataSource(
                            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                            user,
                            password);
            var jdbc = new JdbcTemplate(source);
            // dev에 이미 적용된 원본 V6의 체크섬이다. 주석 수정도 기존 DB의 검증을 깨뜨린다.
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT checksum FROM flyway_schema_history WHERE version = '6'",
                                    Integer.class))
                    .isEqualTo(-137006987);
            jdbc.update(
                    """
                    INSERT INTO product_sales_status
                      (product_id, sales_id, snapshot_version, in_stock, raw_status, created_at, updated_at)
                    VALUES (501, 9001, 1, false, 'SOLD_OUT', now(), now())
                    """);
            jdbc.update(
                    """
                    INSERT INTO notification
                      (member_id, title, body, occurred_at, created_at, updated_at, event_id, event_type)
                    VALUES ('legacy-member', '기존 알림', '보존할 내용', now(), now(), now(), 'legacy-event', 'OrderCreated')
                    """);
            Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT body FROM notification WHERE event_id = 'legacy-event'",
                                    String.class))
                    .isEqualTo("보존할 내용");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = ? AND table_name = 'notification' AND column_name = 'event_id'",
                                    Integer.class,
                                    schema))
                    .isEqualTo(64);
            String longEventId = "payment-expiry-PG-" + UUID.randomUUID();
            String insert =
                    """
                    INSERT INTO notification
                      (member_id, title, body, occurred_at, created_at, updated_at, event_id, event_type)
                    VALUES ('legacy-member', '만료 임박', '새 알림', now(), now(), now(), ?, 'PaymentExpiringSoon')
                    ON CONFLICT (event_id, member_id) DO NOTHING
                    """;
            assertThat(jdbc.update(insert, longEventId)).isEqualTo(1);
            assertThat(jdbc.update(insert, longEventId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_group", Integer.class))
                    .isZero();
            var statuses = new ProductSalesStatusRepository(new NamedParameterJdbcTemplate(source));
            assertThat(statuses.findByProductId(501).orElseThrow().status()).isEqualTo("SOLD_OUT");
            statuses.apply(new ProductSalesStatus(501, 9001, 2, "ON_SALE"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT raw_status FROM product_sales_status WHERE product_id = 501",
                                    String.class))
                    .isEqualTo("ON_SALE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT in_stock FROM product_sales_status WHERE product_id = 501",
                                    Boolean.class))
                    .isTrue();
            jdbc.update(
                    "UPDATE product_sales_status SET snapshot_version = 3, in_stock = false, raw_status = 'PRIVATE' WHERE product_id = 501");
            assertThat(statuses.findByProductId(501).orElseThrow().status()).isEqualTo("PRIVATE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'broadcast_schedule' AND column_name = 'started_at'",
                                    Integer.class,
                                    schema))
                    .isEqualTo(1);
        } finally {
            legacy.clean();
        }
    }

    @DisplayName("마이그레이션으로 알림 테이블을 생성하고 엔티티 매핑을 검증한다")
    @Test
    void migrationCreatesNotificationTableAndValidatesEntityMapping() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE success",
                                Integer.class))
                .isPositive();
        // 알림함 테이블과, 이벤트로 받아 두는 사본 테이블이 모두 만들어져야 한다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name IN ('notification', 'broadcast_schedule')",
                                Integer.class))
                .isEqualTo(2);
    }
}
