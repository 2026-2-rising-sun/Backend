package com.shoppinglive.notification.inbox.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("알림 보관 기간 정리")
class NotificationRetentionTest {

    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private NotificationWriteRepository notifications;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:notification_retention_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().update("DELETE FROM notification");
        notifications = new NotificationWriteRepository(jdbc);
    }

    @Test
    @DisplayName("기준 시각보다 먼저 저장된 알림만 지운다")
    void deletesOnlyOlderNotifications() {
        save("old", NOW.minusSeconds(60));
        save("new", NOW.plusSeconds(60));

        int deleted = notifications.deleteCreatedBefore(NOW, 100);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingEventIds()).containsExactly("new");
    }

    @Test
    @DisplayName("한 번에 지우는 양에 상한이 있고, 남은 것은 다음에 지운다")
    void deletesUpToLimit() {
        save("old-1", NOW.minusSeconds(60));
        save("old-2", NOW.minusSeconds(60));
        save("old-3", NOW.minusSeconds(60));

        assertThat(notifications.deleteCreatedBefore(NOW, 2)).isEqualTo(2);
        assertThat(notifications.deleteCreatedBefore(NOW, 2)).isEqualTo(1);
        assertThat(remainingEventIds()).isEmpty();
    }

    @Test
    @DisplayName("일어난 시각이 과거여도 방금 저장한 알림은 지우지 않는다")
    void keepsRecentlyStoredNotificationWithOldOccurredAt() {
        // 이벤트가 늦게 도착한 경우다. 일어난 시각 기준으로 지우면 방금 만든 알림이 바로 지워진다.
        saveWithOccurredAt("late", NOW.plusSeconds(60), NOW.minus(java.time.Duration.ofDays(60)));

        assertThat(notifications.deleteCreatedBefore(NOW, 100)).isZero();
        assertThat(remainingEventIds()).containsExactly("late");
    }

    private void save(String eventId, Instant createdAt) {
        saveWithOccurredAt(eventId, createdAt, NOW);
    }

    /** 감사 시각을 직접 넣어야 보관 기간을 시험할 수 있어서 저장소를 쓰지 않고 직접 넣는다. */
    private void saveWithOccurredAt(String eventId, Instant createdAt, Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO notification
                    (member_id, event_id, event_type, title, body, link_type, link_value,
                     occurred_at, created_at, updated_at)
                VALUES (:memberId, :eventId, 'Restocked', '재입고되었습니다', '등록하신 상품이 재입고되었습니다',
                        :linkType, '501', :occurredAt, :createdAt, :createdAt)
                """,
                Map.of(
                        "memberId", MEMBER,
                        "eventId", eventId,
                        "linkType", NotificationLinkType.PRODUCT.name(),
                        "occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC),
                        "createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
    }

    private List<String> remainingEventIds() {
        return jdbc.queryForList(
                "SELECT event_id FROM notification ORDER BY event_id", Map.of(), String.class);
    }
}
