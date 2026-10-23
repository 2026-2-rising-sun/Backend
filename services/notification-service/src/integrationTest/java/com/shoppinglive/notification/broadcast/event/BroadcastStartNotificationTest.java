package com.shoppinglive.notification.broadcast.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.broadcast.service.BroadcastStartNotificationService;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 방송 시작 이벤트 하나로 신청자 전원에게 알림이 만들어지는지 본다. broker 는 필요하지 않다. */
@DisplayName("방송 시작 알림 생성")
class BroadcastStartNotificationTest {

    private static final long BROADCAST = 42L;
    private static final String MEMBER_A = "11111111-1111-4111-8111-111111111111";
    private static final String MEMBER_B = "22222222-2222-4222-8222-222222222222";
    private static final String STARTED_AT = "2026-10-20T12:00:00Z";

    private NamedParameterJdbcTemplate jdbc;
    private BroadcastScheduleRepository schedules;
    private BroadcastEventListener listener;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:broadcast_start_notification_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_alert");
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_schedule");
        BroadcastAlertRepository alerts = new BroadcastAlertRepository(jdbc);
        schedules = new BroadcastScheduleRepository(jdbc);
        listener =
                new BroadcastEventListener(
                        schedules,
                        new BroadcastStartNotificationService(
                                alerts, new NotificationWriteRepository(jdbc)));
        alerts.register(MEMBER_A, BROADCAST);
        alerts.register(MEMBER_B, BROADCAST);
    }

    @Test
    @DisplayName("신청자 전원에게 알림 1건씩 만들고, 시각은 실제 시작 시각을 쓴다")
    void createsOneNotificationPerRegistrant() {
        listener.consume(record(started("가을 신상 라이브")));

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(row -> row.get("member_id"))
                .containsExactly(MEMBER_A, MEMBER_B);

        Map<String, Object> first = rows.get(0);
        assertThat(first.get("event_type")).isEqualTo("LiveBroadcastStarted");
        assertThat(first.get("title")).isEqualTo("방송이 시작되었습니다");
        assertThat(first.get("body")).isEqualTo("등록하신 '가을 신상 라이브' 방송이 시작되었습니다");
        assertThat(first.get("link_type")).isEqualTo("BROADCAST");
        assertThat(first.get("link_value")).isEqualTo("42");
        // 받은 시각이 아니라 방송이 실제로 시작한 시각으로 정렬돼야 한다.
        assertThat(occurredAt(first)).isEqualTo(Instant.parse(STARTED_AT));
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 알림은 사람당 하나다")
    void doesNotDuplicateOnRedelivery() {
        listener.consume(record(started("가을 신상 라이브")));
        listener.consume(record(started("가을 신상 라이브")));

        assertThat(notifications()).hasSize(2);
    }

    @Test
    @DisplayName("중간에 신청자가 늘면, 다시 받았을 때 못 만든 사람만 만든다")
    void createsOnlyMissingNotificationsOnRedelivery() {
        listener.consume(record(started("가을 신상 라이브")));
        new com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository(jdbc)
                .register("33333333-3333-4333-8333-333333333333", BROADCAST);

        listener.consume(record(started("가을 신상 라이브")));

        assertThat(notifications()).hasSize(3);
    }

    @Test
    @DisplayName("신청자가 없으면 아무것도 만들지 않는다")
    void createsNothingWithoutRegistrants() {
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_alert");

        listener.consume(record(started("가을 신상 라이브")));

        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("시작 이벤트를 받으면 일정 사본에 시작 시각을 기록한다 — 임박 알림 대상에서 빼는 근거다")
    void recordsStartedAtOnSchedule() {
        // 예정보다 일찍 시작했다. 예정 시각이 아직 10분 뒤라서, 기록하지 않으면
        // 임박 알림 스케줄러가 방송 중인 이 방송을 "곧 시작" 으로 잡는다.
        schedules.apply(
                new BroadcastSchedule(
                        BROADCAST, 1, "가을 신상 라이브", Instant.parse("2026-10-20T12:10:00Z")));

        listener.consume(record(started("가을 신상 라이브")));

        assertThat(startedAt()).isEqualTo(Instant.parse(STARTED_AT));
    }

    @Test
    @DisplayName("일정 사본이 없어도 시작 알림은 만든다")
    void createsStartNotificationsWithoutScheduleCopy() {
        listener.consume(record(started("가을 신상 라이브")));

        assertThat(notifications()).hasSize(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM broadcast_schedule", Map.of(), Integer.class))
                .isZero();
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList(
                """
                SELECT member_id, event_type, title, body, link_type, link_value, occurred_at
                FROM notification ORDER BY member_id
                """,
                Map.of());
    }

    private Instant startedAt() {
        return toInstant(
                jdbc.queryForMap(
                                "SELECT started_at FROM broadcast_schedule"
                                        + " WHERE broadcast_id = :id",
                                Map.of("id", BROADCAST))
                        .get("started_at"));
    }

    private static Instant occurredAt(Map<String, Object> row) {
        return toInstant(row.get("occurred_at"));
    }

    /** 드라이버에 따라 timestamptz 를 OffsetDateTime 이나 Timestamp 로 돌려주므로 둘 다 받는다. */
    private static Instant toInstant(Object value) {
        return value instanceof java.time.OffsetDateTime time
                ? time.toInstant()
                : ((java.sql.Timestamp) value).toInstant();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(
                LiveBroadcastEventContract.TOPIC_NAME, 0, 0L, Long.toString(BROADCAST), value);
    }

    private static String started(String title) {
        return "{\"eventId\":\"74b623b8-90ee-4f65-919c-d9ad2ea46fb2\","
                + "\"eventType\":\"LiveBroadcastStarted\",\"schemaVersion\":1,"
                + "\"source\":\"live-service\",\"aggregateId\":\""
                + BROADCAST
                + "\",\"occurredAt\":\""
                + STARTED_AT
                + "\",\"payload\":{\"broadcastId\":"
                + BROADCAST
                + ",\"broadcastVersion\":2,\"title\":\""
                + title
                + "\",\"scheduledAt\":\"2026-10-20T11:00:00Z\",\"startedAt\":\""
                + STARTED_AT
                + "\",\"productIds\":[1,2]}}";
    }
}
