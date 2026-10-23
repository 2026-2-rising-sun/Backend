package com.shoppinglive.common.observability;

import static org.assertj.core.api.Assertions.*;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class BusinessTelemetryTransactionTest {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    BusinessTelemetry telemetry = new BusinessTelemetry(registry);
    TransactionTemplate tx =
            new TransactionTemplate(
                    new DataSourceTransactionManager(
                            new DriverManagerDataSource("jdbc:h2:mem:telemetry")));

    @Test
    @DisplayName("커밋 전에는 집계하지 않으며 롤백은 확정 성공 지표를 남기지 않는다")
    void countsOnlyCommittedWork() {
        tx.executeWithoutResult(
                status -> {
                    telemetry.count(
                            "sample.result",
                            Tags.of("result", "success"),
                            Map.of("orderNumber", "private-order"));
                    assertThat(registry.find("sample.result").counter()).isNull();
                    status.setRollbackOnly();
                });
        assertThat(registry.find("sample.result").counter()).isNull();
        tx.executeWithoutResult(
                status ->
                        telemetry.count(
                                "sample.result",
                                Tags.of("result", "success"),
                                Map.of("orderNumber", "private-order")));
        assertThat(registry.get("sample.result").counter().count()).isEqualTo(1);
        assertThat(registry.get("sample.result").counter().getId().getTags())
                .containsExactly(io.micrometer.core.instrument.Tag.of("result", "success"));
    }

    @Test
    @DisplayName("외부 트랜잭션 롤백 중에도 실패 시도는 성공과 별도로 기록한다")
    void keepsFailureAttempts() {
        tx.executeWithoutResult(
                status -> {
                    telemetry.failure("sample.failure", Tags.of("result", "rejected"), Map.of());
                    status.setRollbackOnly();
                });
        assertThat(registry.get("sample.failure").counter().count()).isEqualTo(1);
    }
}
