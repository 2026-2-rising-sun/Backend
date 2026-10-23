package com.shoppinglive.notification.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 곧 시작하는 방송의 알림 신청자 합. 방송 시작 전에 Live 파드를 미리 늘리는 근거다.
 *
 * <p>알림 신청({@code broadcast_alert})과 방송 일정 사본({@code broadcast_schedule})이 모두 이 서비스에 있어 다른 서비스를 부르지
 * 않고 한 질의로 센다. 스크레이프마다 DB 를 읽지 않고 주기적으로 집계하며, 실패하면 0 으로 숨기지 않고 NaN 을 노출한다.
 */
public class UpcomingBroadcastAlertMetrics implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UpcomingBroadcastAlertMetrics.class);

    private static final String COUNT_SUBSCRIBERS =
            """
            SELECT count(*)
            FROM broadcast_alert alert
            JOIN broadcast_schedule schedule ON schedule.broadcast_id = alert.broadcast_id
            WHERE schedule.scheduled_at > :now AND schedule.scheduled_at <= :until
              AND schedule.started_at IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration window;
    private final Counter failures;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("observability-upcoming").factory());
    private volatile double subscribers = Double.NaN;

    public UpcomingBroadcastAlertMetrics(
            DataSource source, MeterRegistry registry, Duration window) {
        jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate().setQueryTimeout(2);
        this.window = window;
        failures = registry.counter("shoppinglive.broadcast.upcoming.query.failures");
        Gauge.builder("shoppinglive.broadcast.upcoming.alert.subscribers", this, m -> m.subscribers)
                .description("지금부터 window 안에 시작하는 방송의 알림 신청자 합")
                .register(registry);
    }

    public void start() {
        worker.scheduleWithFixedDelay(() -> sample(Instant.now()), 5, 30, TimeUnit.SECONDS);
    }

    public synchronized void sample(Instant now) {
        try {
            Long count =
                    jdbc.queryForObject(
                            COUNT_SUBSCRIBERS,
                            Map.of(
                                    "now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                                    "until",
                                            OffsetDateTime.ofInstant(
                                                    now.plus(window), ZoneOffset.UTC)),
                            Long.class);
            subscribers = count == null ? 0 : count;
        } catch (RuntimeException failure) {
            subscribers = Double.NaN;
            failures.increment();
            log.warn("곧 시작하는 방송 신청자 집계 실패 type={}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
