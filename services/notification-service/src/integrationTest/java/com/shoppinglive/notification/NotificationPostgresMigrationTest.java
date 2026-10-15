package com.shoppinglive.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.security.NotificationSecuritySupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 PostgreSQL에 Flyway를 적용하고 JPA 매핑 및 알림 테이블 생성을 확인한다. */
@SpringBootTest
@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(
        named = "NOTIFICATION_TEST_POSTGRES_URL",
        matches = "jdbc:postgresql:.*")
class NotificationPostgresMigrationTest extends NotificationSecuritySupport {
    @Autowired DataSource dataSource;

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
