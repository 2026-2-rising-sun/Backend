package com.shoppinglive.payment.observability;

import com.shoppinglive.payment.payment.service.RecoveryProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 기준 시간(12분)을 넘겨 확정되지 않은 결제·시스템 보상·고객 환불 건수. 알림 규칙은 Infra가 맡는다(#387).
 *
 * <p>스크레이프 때만 DB를 읽고 짧게 캐시해 한 번의 스크레이프가 여러 번 조회하지 않게 한다. 조회가 실패하면 값은 NaN이고 실패 카운터만 오른다. 예외를 밖으로 내지
 * 않아 스크레이프와 애플리케이션을 막지 않는다. 행 수가 적은 부분 인덱스(UNKNOWN, 진행 중 보상)를 쓰는 조건이다.
 */
public class UnresolvedWorkMetrics {
    private static final Logger log = LoggerFactory.getLogger(UnresolvedWorkMetrics.class);
    public static final String METRIC = "shoppinglive.payment.unresolved.count";
    public static final String FAILURES = "shoppinglive.payment.unresolved.query.failures";
    static final Duration THRESHOLD = Duration.ofMinutes(12);
    private static final String[] KINDS = {
        "payment_unknown", "compensation_pending", "refund_unknown"
    };
    // 결제 불명 시작 = check_deadline_at − checkDeadline(불명 전환 때 마감을 그만큼 뒤로 잡는다)
    private static final String SQL =
            """
            SELECT (SELECT count(*) FROM payment WHERE status = 'UNKNOWN' AND check_deadline_at < ?),
                   (SELECT count(*) FROM refund WHERE purpose = 'SYSTEM_COMPENSATION'
                       AND status IN ('PROCESSING', 'UNKNOWN') AND created_at < ?),
                   (SELECT count(*) FROM refund WHERE purpose = 'USER_REFUND'
                       AND status = 'UNKNOWN' AND created_at < ?)
            """;

    private final JdbcTemplate jdbc;
    private final Counter failures;
    private final Duration checkDeadline;
    private final Duration cacheTtl;
    private double[] counts = {Double.NaN, Double.NaN, Double.NaN};
    private long sampledAtNanos;
    private boolean sampled;

    public UnresolvedWorkMetrics(
            DataSource source, MeterRegistry registry, RecoveryProperties recovery) {
        this(source, registry, recovery.checkDeadline(), Duration.ofSeconds(5));
    }

    UnresolvedWorkMetrics(
            DataSource source, MeterRegistry registry, Duration checkDeadline, Duration cacheTtl) {
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(2);
        this.checkDeadline = checkDeadline;
        this.cacheTtl = cacheTtl;
        this.failures = registry.counter(FAILURES);
        for (int i = 0; i < KINDS.length; i++) {
            int index = i;
            Gauge.builder(METRIC, this, m -> m.value(index))
                    .tag("kind", KINDS[i])
                    .register(registry);
        }
    }

    private synchronized double value(int index) {
        long now = System.nanoTime();
        if (!sampled || now - sampledAtNanos >= cacheTtl.toNanos()) {
            counts = sample();
            sampledAtNanos = now;
            sampled = true;
        }
        return counts[index];
    }

    private double[] sample() {
        try {
            Instant cutoff = Instant.now().minus(THRESHOLD);
            Timestamp paymentCutoff = Timestamp.from(cutoff.plus(checkDeadline));
            Timestamp refundCutoff = Timestamp.from(cutoff);
            return jdbc.queryForObject(
                    SQL,
                    (rs, row) -> new double[] {rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)},
                    paymentCutoff,
                    refundCutoff,
                    refundCutoff);
        } catch (RuntimeException failure) {
            failures.increment();
            log.warn("미확정 건수 집계 실패 type={}", failure.getClass().getSimpleName());
            return new double[] {Double.NaN, Double.NaN, Double.NaN};
        }
    }
}
