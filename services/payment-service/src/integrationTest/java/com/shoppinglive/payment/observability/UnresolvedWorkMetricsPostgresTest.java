package com.shoppinglive.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 미확정 건수 지표(#387)를 실제 PostgreSQL 행으로 확인한다. 기준 시간은 12분, 결제 불명 시작은 마감 − 30분이다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class UnresolvedWorkMetricsPostgresTest {
    static final String SCHEMA =
            "payment_unresolved_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        p.add("payment.core-service-token", () -> "payment-unresolved-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private UnresolvedWorkMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM refund");
        jdbc.update("DELETE FROM payment");
        registry = new SimpleMeterRegistry();
        // 캐시를 끄고 매 조회를 실제 DB 값으로 본다
        metrics =
                new UnresolvedWorkMetrics(
                        dataSource, registry, Duration.ofMinutes(30), Duration.ZERO);
    }

    @Test
    @DisplayName("불명이 된 지 12분을 넘긴 UNKNOWN 결제만 센다")
    void countsUnknownPaymentsPastTwelveMinutes() {
        insertPayment("PU-OLD", "UNKNOWN", "now() + INTERVAL '17' MINUTE"); // 불명 13분째
        insertPayment("PU-NEW", "UNKNOWN", "now() + INTERVAL '25' MINUTE"); // 불명 5분째

        assertThat(count("payment_unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("보류 중인 시스템 보상(PROCESSING·UNKNOWN)만 생성 후 12분을 넘긴 것을 센다")
    void countsPendingCompensationsPastTwelveMinutes() {
        long old = insertPayment("PC-OLD", "CANCELED", "now()");
        long fresh = insertPayment("PC-NEW", "CANCELED", "now()");
        insertRefund(
                old, "SYSTEM_COMPENSATION", "PROCESSING", "now() - INTERVAL '13' MINUTE", true);
        insertRefund(fresh, "SYSTEM_COMPENSATION", "UNKNOWN", "now() - INTERVAL '1' MINUTE", true);

        assertThat(count("compensation_pending")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("결과를 모르는 고객 환불은 다음 처리 시각이 없어도 센다. 처리 중인 고객 환불은 세지 않는다")
    void countsUnknownUserRefundsEvenWhenRetryStopped() {
        insertRefund(null, "USER_REFUND", "UNKNOWN", "now() - INTERVAL '13' MINUTE", false);
        insertRefund(null, "USER_REFUND", "PROCESSING", "now() - INTERVAL '13' MINUTE", true);

        assertThat(count("refund_unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("확정된 건은 지표에서 빠진다")
    void resolvedItemsLeaveTheGauge() {
        insertRefund(null, "USER_REFUND", "UNKNOWN", "now() - INTERVAL '13' MINUTE", true);
        assertThat(count("refund_unknown")).isEqualTo(1.0);

        jdbc.update(
                "UPDATE refund SET status = 'SUCCEEDED', result_version = 1, result_payload = '{}'");

        assertThat(count("refund_unknown")).isEqualTo(0.0);
    }

    private double count(String kind) {
        return registry.get(UnresolvedWorkMetrics.METRIC).tag("kind", kind).gauge().value();
    }

    private long insertPayment(String orderRef, String status, String checkDeadline) {
        jdbc.update(
                "INSERT INTO payment (order_ref, buyer_id, amount, status, expires_at, pg_provider,"
                        + " check_deadline_at, created_at, updated_at) VALUES (?, ?, 10000, ?,"
                        + " now() + INTERVAL '1' DAY, 'TOSS', "
                        + checkDeadline
                        + ", now(), now())",
                orderRef,
                UUID.randomUUID().toString(),
                status);
        return jdbc.queryForObject(
                "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
    }

    /** 생성 시각 기준 12분 판단을 쓰므로 created_at을 과거로 둘 수 있게 시각식을 받는다. */
    private void insertRefund(
            Long paymentId,
            String purpose,
            String status,
            String createdAt,
            boolean retryScheduled) {
        jdbc.update(
                "INSERT INTO refund (refund_request_id, execution_id, payment_id, purpose, amount,"
                        + " request_payload, status, result_version, refunded_amount, attempt_count,"
                        + " next_attempt_at, check_deadline_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 1000, '{}', ?, "
                        + (status.equals("SUCCEEDED") ? 1 : 0)
                        + ", 0, 1, "
                        + (retryScheduled ? "now()" : "NULL")
                        + ", now() + INTERVAL '1' HOUR, "
                        + createdAt
                        + ", now())",
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                paymentId,
                purpose,
                status);
    }
}
