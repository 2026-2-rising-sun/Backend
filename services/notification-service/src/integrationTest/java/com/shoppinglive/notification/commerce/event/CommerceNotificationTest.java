package com.shoppinglive.notification.commerce.event;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.notification.event.InvalidEventMessageException;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("거래 결과 알림")
class CommerceNotificationTest {
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics;
    private JdbcTemplate jdbc;
    private CommerceNotificationListener listener;
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    private static final String GROUP = "PG-22222222-2222-4222-8222-222222222222";
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:commerce_notification_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL",
                        "sa",
                        "");
        // 연결이 닫혀도 같은 테스트의 사본을 유지한다.
        ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var observation =
                new com.shoppinglive.notification.observability.ConsumerObservation(
                        new com.shoppinglive.common.observability.BusinessTelemetry(metrics));
        var repository =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(
                        new NotificationWriteRepository(new NamedParameterJdbcTemplate(ds)));
        repository.addAspect(observation);
        var delivery =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(
                        new CommerceNotificationListener(repository.getProxy()));
        delivery.addAspect(observation);
        listener = delivery.getProxy();
    }

    @Test
    @DisplayName("결제 묶음당 한 건을 저장하고 같은 이벤트의 재전달은 무시한다")
    void paymentDeduplicatesAndIncludesProduct() throws Exception {
        var event = event("PaymentSucceeded", Map.of("paymentId", 1, "paidAmount", 10000));
        listener.consume(record(event, GROUP));
        listener.consume(record(event, GROUP));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .isEqualTo(1);
        assertThat(metrics.get("shoppinglive.consumer").tag("result", "applied").timer().count())
                .isEqualTo(1);
        assertThat(
                        metrics.get("shoppinglive.consumer")
                                .tag("result", "duplicate_or_no_recipient")
                                .timer()
                                .count())
                .isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM notification");
        assertThat(row.get("member_id")).isEqualTo(MEMBER);
        assertThat(row.get("body").toString()).contains("가을 니트", "10,000원");
        assertThat(row.get("link_type")).isEqualTo("PAYMENT_GROUP");
    }

    @Test
    @DisplayName("실패·취소·만료·부분 환불을 구분하고 환불 상세 식별자를 보존한다")
    void terminalResultsAndRefundLink() throws Exception {
        listener.consume(
                record(
                        event(
                                "PaymentFailed",
                                Map.of(
                                        "paymentId",
                                        1,
                                        "attemptedAmount",
                                        10000,
                                        "failureCode",
                                        "TIMEOUT")),
                        GROUP));
        listener.consume(record(event("OrderCancelled", Map.of("cancelledAmount", 10000)), GROUP));
        listener.consume(record(event("OrderExpired", Map.of("expiredAmount", 10000)), GROUP));
        listener.consume(
                record(
                        event(
                                "RefundCompleted",
                                Map.of(
                                        "refundRequestId",
                                        42,
                                        "refundedAmount",
                                        5000,
                                        "cumulativeRefundAmount",
                                        7500)),
                        GROUP));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .isEqualTo(4);
        var row = jdbc.queryForMap("SELECT * FROM notification WHERE event_type='RefundCompleted'");
        assertThat(row.get("link_type")).isEqualTo("REFUND");
        assertThat(row.get("link_value")).isEqualTo(GROUP + "/42");
        assertThat(row.get("body").toString()).contains("5,000원", "7,500원");
    }

    @Test
    @DisplayName("기존 opaque 쿠폰 ID를 허용하며 사용 만료 시각을 안내한다")
    void couponKeepsOpaqueId() throws Exception {
        Map<String, Object> envelope = event("CouponIssued", Map.of());
        envelope.put("aggregateId", "coupon-opaque-id");
        envelope.put(
                "payload",
                Map.of(
                        "couponId",
                        "coupon-opaque-id",
                        "memberId",
                        MEMBER,
                        "name",
                        "가을 쿠폰",
                        "fixedDiscount",
                        1000,
                        "expiresAt",
                        "2026-12-01T00:00:00Z"));
        listener.consume(
                new ConsumerRecord<>(
                        "commerce.coupon-info.v1",
                        0,
                        1,
                        "coupon-opaque-id",
                        mapper.writeValueAsString(envelope)));
        assertThat(jdbc.queryForObject("SELECT link_type FROM notification", String.class))
                .isEqualTo("COUPON");
    }

    @Test
    @DisplayName("결과 미확정·생성 이벤트와 기존 쿠폰 표시 이벤트는 즉시 알림을 만들지 않는다")
    void ignoresNonResults() throws Exception {
        for (String type : List.of("PaymentGroupCreated", "PaymentUnknown", "FutureEvent"))
            listener.consume(record(event(type, Map.of()), GROUP));
        listener.consume(
                new ConsumerRecord<>(
                        "commerce.coupon-info.v1",
                        0,
                        1,
                        "coupon",
                        "{\"eventType\":\"CouponUpdated\"}"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("잘못된 key·수신자·금액·봉투는 저장 전에 거부한다")
    void rejectsInvalidBeforeWrite() throws Exception {
        var valid = event("PaymentSucceeded", Map.of("paymentId", 1, "paidAmount", 10000));
        assertThatThrownBy(() -> listener.consume(record(valid, "other")))
                .isInstanceOf(InvalidEventMessageException.class);
        var negative = event("PaymentSucceeded", Map.of("paymentId", 1, "paidAmount", -1));
        assertThatThrownBy(() -> listener.consume(record(negative, GROUP)))
                .isInstanceOf(InvalidEventMessageException.class);
        valid.put("source", "other-service");
        assertThatThrownBy(() -> listener.consume(record(valid, GROUP)))
                .isInstanceOf(InvalidEventMessageException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("마이그레이션으로 보존한 기존 묶음의 취소·환불 알림도 생성한다")
    void acceptsLegacyGroupNumber() throws Exception {
        String number = "PG-LEGACY-42";
        for (String type : List.of("OrderCancelled", "RefundCompleted")) {
            var envelope =
                    event(
                            type,
                            type.equals("OrderCancelled")
                                    ? Map.of("cancelledAmount", 10000)
                                    : Map.of(
                                            "refundRequestId",
                                            42,
                                            "refundedAmount",
                                            5000,
                                            "cumulativeRefundAmount",
                                            5000));
            envelope.put("aggregateId", number);
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) envelope.get("payload");
            payload.put("groupNumber", number);
            listener.consume(record(envelope, number));
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT link_value FROM notification ORDER BY id", String.class))
                .containsExactly(number, number + "/42");
    }

    private Map<String, Object> event(String type, Map<String, Object> details) {
        var payload = new LinkedHashMap<String, Object>();
        payload.putAll(
                Map.of(
                        "paymentGroupId",
                        1,
                        "groupNumber",
                        GROUP,
                        "memberId",
                        MEMBER,
                        "currency",
                        "KRW",
                        "orderCount",
                        1,
                        "orders",
                        List.of(
                                Map.of(
                                        "orderNumber",
                                        "O-1",
                                        "productName",
                                        "가을 니트",
                                        "amount",
                                        10000)),
                        "expiresAt",
                        "2026-12-01T00:00:00Z"));
        payload.putAll(details);
        return new LinkedHashMap<>(
                Map.of(
                        "eventId",
                        UUID.randomUUID().toString(),
                        "eventType",
                        type,
                        "schemaVersion",
                        1,
                        "source",
                        "commerce-service",
                        "aggregateId",
                        GROUP,
                        "occurredAt",
                        "2026-10-17T00:00:00Z",
                        "payload",
                        payload));
    }

    private ConsumerRecord<String, String> record(Map<String, Object> event, String key)
            throws Exception {
        return new ConsumerRecord<>(
                "commerce.payment-group.v1", 0, 1, key, mapper.writeValueAsString(event));
    }
}
