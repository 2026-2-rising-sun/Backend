package com.shoppinglive.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnresolvedWorkMetricsTest {

    @Test
    @DisplayName("DB 조회가 실패하면 gauge는 NaN이고 실패 카운터만 늘어난다. 스크레이프는 예외 없이 끝난다")
    void queryFailureReadsNanAndCountsFailureWithoutThrowing() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("db down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new UnresolvedWorkMetrics(broken, registry, Duration.ofMinutes(30), Duration.ZERO);

        for (String kind : new String[] {"payment_unknown", "compensation_pending", "refund_unknown"}) {
            Gauge gauge =
                    registry.get(UnresolvedWorkMetrics.METRIC).tag("kind", kind).gauge();
            assertThat(gauge.value()).isNaN();
        }
        assertThat(registry.counter(UnresolvedWorkMetrics.FAILURES).count()).isEqualTo(3.0);
    }
}
