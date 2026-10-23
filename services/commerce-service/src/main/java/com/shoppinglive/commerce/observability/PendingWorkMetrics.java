package com.shoppinglive.commerce.observability;

import io.micrometer.core.instrument.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 스크레이프마다 DB를 읽지 않는다. 실패 시 0으로 숨기지 않고 NaN·갱신 시각을 노출한다. */
public class PendingWorkMetrics implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PendingWorkMetrics.class);
    private final JdbcTemplate jdbc;
    private final Map<String, java.util.concurrent.atomic.AtomicReference<double[]>> values =
            new LinkedHashMap<>();
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("observability-db").factory());
    private volatile double sampledAt = Double.NaN;
    private final Counter failures;
    private static final Map<String, String> CONDITIONS =
            Map.of(
                    "payment", "payment_attempt WHERE status IN ('PENDING','PROCESSING','UNKNOWN')",
                    "refund", "refund_request WHERE status IN ('PROCESSING','UNKNOWN')",
                    "refund_retry_exhausted",
                            "refund_request WHERE status IN ('PROCESSING','UNKNOWN') AND retry_count>=3",
                    "outbox", "event_outbox WHERE published_at IS NULL");

    public PendingWorkMetrics(DataSource source, MeterRegistry registry) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        failures = registry.counter("shoppinglive.pending.query.failures");
        for (String kind : CONDITIONS.keySet()) {
            var value =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            new double[] {Double.NaN, Double.NaN});
            values.put(kind, value);
            Gauge.builder("shoppinglive.pending.count", value, v -> v.get()[0])
                    .tag("kind", kind)
                    .register(registry);
            Gauge.builder("shoppinglive.pending.oldest.seconds", value, v -> v.get()[1])
                    .tag("kind", kind)
                    .register(registry);
        }
        Gauge.builder("shoppinglive.pending.sample.timestamp.seconds", this, v -> v.sampledAt)
                .register(registry);
    }

    public void start() {
        worker.scheduleWithFixedDelay(this::sample, 5, 30, TimeUnit.SECONDS);
    }

    public synchronized void sample() {
        try {
            Map<String, double[]> fresh = new HashMap<>();
            for (var item : CONDITIONS.entrySet()) {
                fresh.put(
                        item.getKey(),
                        jdbc.queryForObject(
                                "SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-min(created_at))),0) FROM "
                                        + item.getValue(),
                                (rs, n) ->
                                        new double[] {
                                            rs.getDouble(1), Math.max(0, rs.getDouble(2))
                                        }));
            }
            fresh.forEach(
                    (kind, value) -> {
                        values.get(kind).set(value);
                    });
            sampledAt = Instant.now().getEpochSecond();
        } catch (RuntimeException failure) {
            values.values()
                    .forEach(
                            v -> {
                                v.set(new double[] {Double.NaN, Double.NaN});
                            });
            failures.increment();
            log.warn("미해결 업무 집계 실패 type={}", failure.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
