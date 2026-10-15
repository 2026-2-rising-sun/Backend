package com.shoppinglive.notification.broadcast.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 스케줄러가 부르는 쓸기 동작을 직접 불러 검증한다. */
@DisplayName("방송 시작 임박 알림")
class BroadcastStartingSoonTest {

    private static final long BROADCAST = 42L;
    private static final String MEMBER_A = "11111111-1111-4111-8111-111111111111";
    private static final Instant NOW = Instant.parse("2026-10-20T11:55:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private BroadcastScheduleRepository schedules;
    private BroadcastAlertRepository alerts;
    private BroadcastStartingSoonService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:broadcast_starting_soon_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
        schedules = new BroadcastScheduleRepository(jdbc);
        alerts = new BroadcastAlertRepository(jdbc);
        service =
                new BroadcastStartingSoonService(
                        schedules, alerts, new NotificationWriteRepository(jdbc));
        alerts.register(MEMBER_A, BROADCAST);
    }

    @Test
    @DisplayName("10분 안에 시작하는 방송의 신청자에게 알림을 만든다")
    void notifiesRegistrantsOfBroadcastStartingSoon() {
        schedule("2026-10-20T12:00:00Z");

        assertThat(service.notifyStartingSoon(NOW)).isEqualTo(1);

        Map<String, Object> row = notifications().get(0);
        assertThat(row.get("event_type")).isEqualTo("BroadcastStartingSoon");
        assertThat(row.get("title")).isEqualTo("곧 방송이 시작됩니다");
        assertThat(row.get("body")).isEqualTo("등록하신 '가을 신상 라이브' 방송이 10분 뒤 시작됩니다");
        assertThat(row.get("link_value")).isEqualTo("42");
    }

    @Test
    @DisplayName("여러 번 돌려도 방송당 한 번만 보낸다")
    void sendsOnlyOncePerBroadcast() {
        schedule("2026-10-20T12:00:00Z");

        assertThat(service.notifyStartingSoon(NOW)).isEqualTo(1);
        assertThat(service.notifyStartingSoon(NOW)).isZero();
        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("일정이 바뀌어도 임박 알림은 한 번이다")
    void doesNotResendAfterScheduleChange() {
        schedule("2026-10-20T12:00:00Z");
        service.notifyStartingSoon(NOW);

        // 일정 이벤트가 다시 와서 사본이 갱신됐다. 보냈다는 표시는 유지된다.
        schedules.apply(
                new BroadcastSchedule(
                        BROADCAST, 2, "가을 신상 라이브", Instant.parse("2026-10-20T12:03:00Z")));

        assertThat(service.notifyStartingSoon(NOW)).isZero();
        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("아직 먼 방송은 보내지 않는다")
    void skipsBroadcastsFurtherAway() {
        schedule("2026-10-20T13:00:00Z");

        assertThat(service.notifyStartingSoon(NOW)).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("이미 시작 시각이 지난 방송은 보내지 않는다 — 복구 뒤 과거 알림이 쏟아지면 안 된다")
    void skipsBroadcastsAlreadyStarted() {
        schedule("2026-10-20T11:50:00Z");

        assertThat(service.notifyStartingSoon(NOW)).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("신청자가 없으면 알림은 없지만 다시 보지 않도록 표시한다")
    void marksSentEvenWithoutRegistrants() {
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_alert");
        schedule("2026-10-20T12:00:00Z");

        assertThat(service.notifyStartingSoon(NOW)).isZero();
        assertThat(schedules.findStartingSoon(NOW, NOW.plusSeconds(600), 10)).isEmpty();
    }

    private void schedule(String scheduledAt) {
        schedules.apply(
                new BroadcastSchedule(BROADCAST, 1, "가을 신상 라이브", Instant.parse(scheduledAt)));
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList(
                "SELECT member_id, event_type, title, body, link_value FROM notification",
                Map.of());
    }
}
