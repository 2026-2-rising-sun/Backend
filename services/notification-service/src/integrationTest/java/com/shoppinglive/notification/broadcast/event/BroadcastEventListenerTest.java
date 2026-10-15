package com.shoppinglive.notification.broadcast.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.broadcast.service.BroadcastStartNotificationService;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Instant;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 리스너를 직접 불러 "무엇을 건너뛰고 무엇을 재시도하는가" 를 검증한다. broker 는 필요하지 않다. */
@DisplayName("방송 일정 수신")
class BroadcastEventListenerTest {

    private static final long BROADCAST = 42L;
    private static final String EVENT_ID = "74b623b8-90ee-4f65-919c-d9ad2ea46fb2";

    private NamedParameterJdbcTemplate jdbc;
    private BroadcastScheduleRepository schedules;
    private BroadcastEventListener listener;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:broadcast_event_listener_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_schedule");
        schedules = new BroadcastScheduleRepository(jdbc);
        listener = new BroadcastEventListener(schedules, startNotifications(jdbc));
    }

    @Test
    @DisplayName("일정 이벤트를 받으면 사본을 저장한다")
    void storesScheduleFromEvent() {
        listener.consume(record(scheduled(1, "가을 신상 라이브", "2026-10-20T12:00:00Z")));

        BroadcastSchedule stored = schedules.findById(BROADCAST).orElseThrow();
        assertThat(stored.title()).isEqualTo("가을 신상 라이브");
        assertThat(stored.scheduledAt()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도, 늦게 온 옛 일정이 새 일정을 덮지 않는다")
    void ignoresDuplicateAndStaleEvents() {
        listener.consume(record(scheduled(2, "변경된 제목", "2026-10-20T13:00:00Z")));
        // 중복 전달: version 이 같아서 반영되지 않는다.
        listener.consume(record(scheduled(2, "변경된 제목", "2026-10-20T13:00:00Z")));
        // 순서 역전: 먼저 발행된 version 1 이 늦게 도착했다.
        listener.consume(record(scheduled(1, "처음 제목", "2026-10-20T12:00:00Z")));

        BroadcastSchedule stored = schedules.findById(BROADCAST).orElseThrow();
        assertThat(stored.broadcastVersion()).isEqualTo(2);
        assertThat(stored.title()).isEqualTo("변경된 제목");
    }

    @Test
    @DisplayName("알림이 쓰지 않는 이벤트 종류는 조용히 넘어간다")
    void ignoresUnknownEventTypes() {
        String ended =
                envelope(
                        "LiveBroadcastEnded",
                        "{\"broadcastId\":42,\"broadcastVersion\":3,"
                                + "\"startedAt\":\"2026-10-20T12:00:00Z\","
                                + "\"endedAt\":\"2026-10-20T13:00:00Z\"}");

        assertThatCode(() -> listener.consume(record(ended))).doesNotThrowAnyException();
        assertThat(schedules.findById(BROADCAST)).isEmpty();
    }

    @Test
    @DisplayName("다시 읽어도 실패할 메시지는 건너뛴다 — 재시도하면 뒤의 알림이 모두 멈춘다")
    void skipsMessagesThatCanNeverSucceed() {
        assertThatCode(
                        () -> {
                            listener.consume(record("깨진 JSON"));
                            // 발행 서비스가 계약과 다르다.
                            listener.consume(
                                    record(
                                            envelope("LiveBroadcastScheduled", "{}")
                                                    .replace("live-service", "attacker")));
                            // 메시지 key 가 payload 의 방송 ID 와 다르면 파티션이 흩어져 순서가 깨진 것이다.
                            listener.consume(
                                    new ConsumerRecord<>(
                                            LiveBroadcastEventContract.TOPIC_NAME,
                                            0,
                                            0L,
                                            "999",
                                            scheduled(1, "가을 신상 라이브", "2026-10-20T12:00:00Z")));
                        })
                .doesNotThrowAnyException();

        assertThat(schedules.findById(BROADCAST)).isEmpty();
    }

    @Test
    @DisplayName("DB 장애는 던져서 재시도한다 — 버리면 알림이 유실된다")
    void rethrowsTransientFailures() {
        BroadcastScheduleRepository failing = mock(BroadcastScheduleRepository.class);
        when(failing.apply(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(
                        () ->
                                new BroadcastEventListener(failing, startNotifications(jdbc))
                                        .consume(
                                                record(
                                                        scheduled(
                                                                1,
                                                                "가을 신상 라이브",
                                                                "2026-10-20T12:00:00Z"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static BroadcastStartNotificationService startNotifications(
            NamedParameterJdbcTemplate jdbc) {
        return new BroadcastStartNotificationService(
                new BroadcastAlertRepository(jdbc), new NotificationWriteRepository(jdbc));
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(
                LiveBroadcastEventContract.TOPIC_NAME, 0, 0L, Long.toString(BROADCAST), value);
    }

    private static String scheduled(long version, String title, String scheduledAt) {
        return envelope(
                "LiveBroadcastScheduled",
                "{\"broadcastId\":"
                        + BROADCAST
                        + ",\"broadcastVersion\":"
                        + version
                        + ",\"title\":\""
                        + title
                        + "\",\"scheduledAt\":\""
                        + scheduledAt
                        + "\"}");
    }

    private static String envelope(String eventType, String payload) {
        return "{\"eventId\":\""
                + EVENT_ID
                + "\",\"eventType\":\""
                + eventType
                + "\",\"schemaVersion\":1,\"source\":\"live-service\",\"aggregateId\":\""
                + BROADCAST
                + "\",\"occurredAt\":\"2026-10-15T00:00:00Z\",\"payload\":"
                + payload
                + "}";
    }
}
