package com.shoppinglive.notification.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 집계 질의를 운영과 같은 스키마(Flyway, H2 PostgreSQL 모드)에서 직접 불러 검증한다. */
@DisplayName("곧 시작하는 방송의 알림 신청자 지표")
class UpcomingBroadcastAlertMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-20T11:55:00Z");
    private static final String MEMBER_A = "11111111-1111-4111-8111-111111111111";
    private static final String MEMBER_B = "22222222-2222-4222-8222-222222222222";

    private DataSource dataSource;
    private BroadcastScheduleRepository schedules;
    private BroadcastAlertRepository alerts;
    private SimpleMeterRegistry meters;
    private UpcomingBroadcastAlertMetrics metrics;

    @BeforeEach
    void setUp() {
        dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:upcoming_alert_metrics_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_alert");
        jdbc.getJdbcTemplate().update("DELETE FROM broadcast_schedule");
        schedules = new BroadcastScheduleRepository(jdbc);
        alerts = new BroadcastAlertRepository(jdbc);
        meters = new SimpleMeterRegistry();
        metrics = new UpcomingBroadcastAlertMetrics(dataSource, meters, Duration.ofMinutes(10));
    }

    @AfterEach
    void stop() {
        metrics.close();
    }

    @Test
    @DisplayName("10분 안에 시작하는 방송의 신청자만 더한다")
    void sumsSubscribersOfBroadcastsStartingWithinWindow() {
        schedule(1L, "2026-10-20T12:00:00Z"); // 5분 뒤
        schedule(2L, "2026-10-20T12:30:00Z"); // 35분 뒤
        schedule(3L, "2026-10-20T11:50:00Z"); // 이미 지남
        alerts.register(MEMBER_A, 1L);
        alerts.register(MEMBER_B, 1L);
        alerts.register(MEMBER_A, 2L);
        alerts.register(MEMBER_A, 3L);

        metrics.sample(NOW);

        assertThat(gauge()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("집계 전에는 NaN 이고, 신청이 없으면 0 이다")
    void startsAsNaNAndReportsZeroWithoutSubscribers() {
        assertThat(gauge()).isNaN();

        metrics.sample(NOW);

        assertThat(gauge()).isZero();
    }

    @Test
    @DisplayName("질의가 실패하면 0 으로 숨기지 않고 NaN 과 실패 횟수를 남긴다")
    void exposesNaNAndFailureCountWhenQueryFails() {
        // 마이그레이션하지 않은 빈 DB 라 표가 없어 질의가 실패한다. 공유 DB 의 표를 지우면 다른 테스트가 깨진다.
        DataSource empty =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:upcoming_alert_metrics_empty;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        SimpleMeterRegistry isolated = new SimpleMeterRegistry();
        try (UpcomingBroadcastAlertMetrics broken =
                new UpcomingBroadcastAlertMetrics(empty, isolated, Duration.ofMinutes(10))) {
            broken.sample(NOW);
        }

        assertThat(
                        isolated.get("shoppinglive.broadcast.upcoming.alert.subscribers")
                                .gauge()
                                .value())
                .isNaN();
        assertThat(isolated.get("shoppinglive.broadcast.upcoming.query.failures").counter().count())
                .isEqualTo(1.0);
    }

    private void schedule(long broadcastId, String scheduledAt) {
        schedules.apply(
                new BroadcastSchedule(
                        broadcastId, 1, "라이브 " + broadcastId, Instant.parse(scheduledAt)));
    }

    private double gauge() {
        return meters.get("shoppinglive.broadcast.upcoming.alert.subscribers").gauge().value();
    }
}
