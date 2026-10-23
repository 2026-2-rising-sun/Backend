package com.shoppinglive.notification.commerce.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.notification.commerce.entity.PaymentGroup;
import com.shoppinglive.notification.commerce.event.CommerceNotificationListener;
import com.shoppinglive.notification.commerce.event.PaymentGroupTracker;
import com.shoppinglive.notification.commerce.repository.PaymentGroupRepository;
import com.shoppinglive.notification.event.InvalidEventMessageException;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 만료 5분 전에 알림이 나가는지, 끝난 묶음에는 안 나가는지 확인한다. broker 는 필요하지 않다. */
@DisplayName("결제 만료 임박 알림")
class PaymentExpiringSoonTest {

    private static final String GROUP = "PG-3f2a1b7c-9d4e-4a8b-b1c2-0e5f6a7b8c9d";
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    private static final String EXPIRES_AT = "2026-10-20T12:00:00Z";

    /** 만료 5분 전. 알림이 나가야 하는 시각이다. */
    private static final Instant NOW = Instant.parse("2026-10-20T11:56:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private org.springframework.transaction.PlatformTransactionManager transactions;
    private PaymentGroupRepository groups;
    private PaymentExpiringSoonService service;
    private CommerceNotificationListener listener;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                com.shoppinglive.notification.NotificationTestDatabase.create(
                        "payment_expiring_soon_test");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().update("DELETE FROM notification");
        jdbc.getJdbcTemplate().update("DELETE FROM payment_group");
        groups = new PaymentGroupRepository(jdbc);
        transactions =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        NotificationWriteRepository notifications = new NotificationWriteRepository(jdbc);
        service =
                new PaymentExpiringSoonService(
                        groups, notifications, Duration.ofMinutes(5), transactions);
        listener = new CommerceNotificationListener(notifications, new PaymentGroupTracker(groups));
    }

    @Test
    @DisplayName("알림 저장이 실패하면 발송 표시를 되돌리고 다음 주기에 다시 보낸다")
    void rollsBackClaimWhenNotificationInsertFails() {
        listener.consume(record(created()));
        var writes = mock(NotificationWriteRepository.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("의도한 저장 실패"))
                .when(writes)
                .createAll(any(), any());
        var failing =
                new PaymentExpiringSoonService(groups, writes, Duration.ofMinutes(5), transactions);

        assertThat(failing.notifyExpiringSoon(NOW)).isZero();
        assertThat(groups.findExpiringSoon(NOW, NOW.plusSeconds(300), 100)).hasSize(1);
        assertThat(service.notifyExpiringSoon(NOW)).isEqualTo(1);
        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("스케줄러 두 개가 동시에 실행돼도 만료 임박 알림은 하나만 만든다")
    void concurrentSchedulersCreateOneNotification() throws Exception {
        listener.consume(record(created()));
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> task =
                    () -> {
                        start.await();
                        return service.notifyExpiringSoon(NOW);
                    };
            var first = workers.submit(task);
            var second = workers.submit(task);
            start.countDown();
            assertThat(
                            first.get(10, java.util.concurrent.TimeUnit.SECONDS)
                                    + second.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("대상을 조회한 직후 결제가 끝나면 만료 임박 알림을 만들지 않는다")
    void skipsGroupClosedAfterCandidateQuery() {
        listener.consume(record(created()));
        var racing = spy(groups);
        doAnswer(
                        call -> {
                            Object candidates = call.callRealMethod();
                            groups.close(GROUP, NOW);
                            return candidates;
                        })
                .when(racing)
                .findExpiringSoon(any(), any(), anyInt());
        var racingService =
                new PaymentExpiringSoonService(
                        racing,
                        new NotificationWriteRepository(jdbc),
                        Duration.ofMinutes(5),
                        transactions);

        assertThat(racingService.notifyExpiringSoon(NOW)).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("묶음 생성 이벤트를 받으면 사본을 저장한다")
    void storesGroupOnCreated() {
        listener.consume(record(created()));

        PaymentGroup stored = groups.findExpiringSoon(NOW, NOW.plusSeconds(600), 10).get(0);
        assertThat(stored.groupNumber()).isEqualTo(GROUP);
        assertThat(stored.memberId()).isEqualTo(MEMBER);
        assertThat(stored.productSummary()).isEqualTo("가을 니트 외 2건");
        assertThat(stored.expiresAt()).isEqualTo(Instant.parse(EXPIRES_AT));
    }

    @Test
    @DisplayName("만료 5분 전이 되면 알림을 만든다")
    void notifiesBeforeExpiry() {
        listener.consume(record(created()));

        assertThat(service.notifyExpiringSoon(NOW)).isEqualTo(1);

        Map<String, Object> row = notifications().get(0);
        assertThat(row.get("member_id")).isEqualTo(MEMBER);
        assertThat(row.get("event_type")).isEqualTo("PaymentExpiringSoon");
        assertThat(row.get("title")).isEqualTo("결제 시간이 곧 만료됩니다");
        assertThat(row.get("body")).isEqualTo("가을 니트 외 2건, 5분 뒤 결제가 만료됩니다");
        assertThat(row.get("link_type")).isEqualTo("PAYMENT_GROUP");
        assertThat(row.get("link_value")).isEqualTo(GROUP);
    }

    @Test
    @DisplayName("아직 멀면 보내지 않는다")
    void skipsGroupsFurtherAway() {
        listener.consume(record(created()));

        // 만료 10분 전. 아직 5분 창에 들어오지 않았다.
        assertThat(service.notifyExpiringSoon(Instant.parse("2026-10-20T11:50:00Z"))).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("이미 만료 시각이 지났으면 보내지 않는다 — 복구 뒤 과거 알림이 쏟아지면 안 된다")
    void skipsAlreadyExpiredGroups() {
        listener.consume(record(created()));

        assertThat(service.notifyExpiringSoon(Instant.parse("2026-10-20T12:01:00Z"))).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("여러 번 돌려도 묶음당 한 번만 보낸다")
    void sendsOnlyOncePerGroup() {
        listener.consume(record(created()));

        assertThat(service.notifyExpiringSoon(NOW)).isEqualTo(1);
        assertThat(service.notifyExpiringSoon(NOW)).isZero();
        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("같은 묶음 생성 이벤트가 두 번 와도 사본은 하나다")
    void ignoresDuplicateCreated() {
        listener.consume(record(created()));
        listener.consume(record(created()));

        assertThat(count("payment_group")).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} 뒤에는 임박 알림을 보내지 않는다")
    @ValueSource(strings = {"PaymentSucceeded", "OrderCancelled", "OrderExpired"})
    @DisplayName("끝난 묶음에는 보내지 않는다")
    void skipsClosedGroups(String closingType) {
        listener.consume(record(created()));
        listener.consume(record(closing(closingType)));

        assertThat(service.notifyExpiringSoon(NOW)).isZero();
        // 끝났다는 알림 1건만 있고 임박 알림은 없다.
        assertThat(notifications()).hasSize(1);
        assertThat(notifications().get(0).get("event_type")).isEqualTo(closingType);
    }

    @Test
    @DisplayName("결제 실패는 묶음을 끝내지 않는다 — 만료 전까지 다시 시도할 수 있다")
    void paymentFailureKeepsGroupOpen() {
        listener.consume(record(created()));
        listener.consume(record(closing("PaymentFailed")));

        assertThat(service.notifyExpiringSoon(NOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("사본이 없는 묶음이 끝나도 넘어간다 — 구독 전에 만들어진 묶음이다")
    void toleratesClosingUnknownGroup() {
        listener.consume(record(closing("PaymentSucceeded")));

        assertThat(count("payment_group")).isZero();
    }

    @Test
    @DisplayName("메시지 key 가 묶음 번호와 다르면 보관함으로 보내도록 던진다")
    void throwsWhenKeyDoesNotMatch() {
        assertThatThrownBy(
                        () ->
                                listener.consume(
                                        new ConsumerRecord<>(
                                                "commerce.payment-group.v1",
                                                0,
                                                0L,
                                                "PG-other",
                                                created())))
                .isInstanceOf(InvalidEventMessageException.class);

        assertThat(count("payment_group")).isZero();
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList(
                """
                SELECT member_id, event_type, title, body, link_type, link_value
                FROM notification ORDER BY id
                """,
                Map.of());
    }

    private int count(String table) {
        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM " + table, Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("commerce.payment-group.v1", 0, 0L, GROUP, value);
    }

    private static String created() {
        return envelope("PaymentGroupCreated", "11111111-1111-4111-8111-aaaaaaaaaaaa", "");
    }

    /** 끝내는 이벤트는 종류마다 필수 필드가 달라서, 알림 쪽 검증을 통과하도록 그 필드를 함께 채운다. */
    private static String closing(String eventType) {
        String extra =
                switch (eventType) {
                    case "PaymentSucceeded" ->
                            """
                            ,"paymentId": 1, "paidAmount": 30000""";
                    case "PaymentFailed" ->
                            """
                            ,"paymentId": 1, "attemptedAmount": 30000, "failureCode": "TIMEOUT\"""";
                    case "OrderCancelled" ->
                            """
                            ,"cancelledAmount": 30000""";
                    default ->
                            """
                            ,"expiredAmount": 30000""";
                };
        // 종류마다 다른 eventId 를 줘야 한 테스트에서 알림이 서로 중복으로 걸리지 않는다.
        return envelope(
                eventType, UUID.nameUUIDFromBytes(eventType.getBytes(UTF_8)).toString(), extra);
    }

    /** 세 주문이 담긴 결제 묶음 이벤트. 상품명 셋이라 문구가 "가을 니트 외 2건" 이 된다. */
    private static String envelope(String eventType, String eventId, String extraPayload) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "schemaVersion": 1,
                  "source": "commerce-service",
                  "aggregateId": "%s",
                  "occurredAt": "2026-10-20T11:45:00Z",
                  "payload": {
                    "paymentGroupId": 1,
                    "groupNumber": "%s",
                    "memberId": "%s",
                    "currency": "KRW",
                    "orderCount": 3,
                    "expiresAt": "%s",
                    "orders": [
                      {"orderNumber": "ORD-1", "productName": "가을 니트", "amount": 10000},
                      {"orderNumber": "ORD-2", "productName": "겨울 코트", "amount": 10000},
                      {"orderNumber": "ORD-3", "productName": "목도리", "amount": 10000}
                    ]%s
                  }
                }
                """
                .formatted(eventId, eventType, GROUP, GROUP, MEMBER, EXPIRES_AT, extraPayload);
    }
}
