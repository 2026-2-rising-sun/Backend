package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.coupon.service.CouponClaimService;
import com.shoppinglive.commercecore.order.dto.CreateOrderCommand;
import com.shoppinglive.commercecore.order.service.OrderCreationService;
import com.shoppinglive.commercecore.order.service.PaymentGroupService;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.common.outbox.OutboxProperties;
import com.shoppinglive.common.outbox.OutboxRelay;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 PostgreSQL 마이그레이션과 별도 스키마에서 업무 변경·이벤트 기록을 함께 검증한다. */
@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@EmbeddedKafka(
        partitions = 1,
        topics = {"commerce.payment-group.v1", "commerce.coupon-info.v1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
/** 결제와 무관한 이벤트 기록 테스트만 남겼다. 옛 Payment로 결제·환불을 끝내던 테스트는 #347에서 지웠다. */
class NotificationEventPostgresTest extends RefundTestSupport {
    private static final String SCHEMA =
            "notification_events_" + UUID.randomUUID().toString().replace("-", "");
    private static final String COUPON = "event-coupon";
    @Autowired ObjectMapper mapper;
    @Autowired CouponClaimService coupons;
    @Autowired OrderCreationService singleCreation;
    @MockitoSpyBean EventOutbox eventOutbox;
    @Autowired OutboxMessageRepository outbox;
    @Autowired EmbeddedKafkaBroker broker;

    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    private static String databaseUrl() {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    @BeforeEach
    void clearEventsAndCoupon() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", COUPON);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", COUPON);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", COUPON);
    }

    private PaymentGroupService.GroupResponse create(String key) {
        return paymentGroups
                .create(
                        MEMBER_A,
                        List.of(
                                new PaymentGroupService.Selection(a.getId(), a.getVersion()),
                                new PaymentGroupService.Selection(b.getId(), b.getVersion())),
                        "구매자",
                        "01012345678",
                        25000L,
                        key)
                .group();
    }

    private List<JsonNode> events(String type) {
        return jdbc.query(
                "SELECT payload FROM event_outbox WHERE event_type=? ORDER BY id",
                (rs, row) -> {
                    try {
                        return mapper.readTree(rs.getString(1));
                    } catch (java.io.IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                },
                type);
    }

    @Test
    @DisplayName("취소·만료 이벤트 기록 실패는 주문 종료와 재고 복구도 롤백한다")
    void cancellationAndExpiryRollbackTogetherWithEvents() {
        var group = create("close-rollback");
        failAfterRecording("OrderCancelled");
        assertThatThrownBy(() -> paymentGroups.cancel(MEMBER_A, group.groupNumber()))
                .isInstanceOf(RuntimeException.class);
        assertThat(events("OrderCancelled")).isEmpty();
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status().name())
                .isEqualTo("PENDING_PAYMENT");
        reset(outboxTarget());
        long id =
                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        jdbc.update(
                "UPDATE payment_group SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                id);
        failAfterRecording("OrderExpired");
        assertThatThrownBy(() -> paymentGroups.expire(id)).isInstanceOf(RuntimeException.class);
        assertThat(events("OrderExpired")).isEmpty();
        // 공개 조회도 만료를 다시 시도하므로 장애 주입 중에는 원본에서 롤백을 확인한다.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_group WHERE id=?", String.class, id))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM orders WHERE payment_group_id=?",
                                String.class,
                                id))
                .containsOnly("PENDING_PAYMENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(reserved) FROM sku WHERE id IN (SELECT sku_id FROM orders WHERE payment_group_id=?)",
                                Long.class,
                                id))
                .isEqualTo(3);
        reset(outboxTarget());
        assertThat(paymentGroups.expire(id)).isTrue();
        assertThat(events("OrderExpired")).hasSize(1);
    }

    private EventOutbox outboxTarget() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(eventOutbox);
    }

    private void failAfterRecording(String eventType) {
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            EventEnvelope<?> event = call.getArgument(1);
                            if (event.eventType().equals(eventType))
                                throw new IllegalStateException("이벤트 기록 후 장애 주입");
                            return null;
                        })
                .when(outboxTarget())
                .append(anyString(), any());
    }

    private void defineCoupon(long discount, int limit) {
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,
                starts_at,ends_at,expires_at,created_at) VALUES (?,?,?, ?,?,?, ?,?,?)
                """,
                COUPON,
                SELLER,
                "알림 쿠폰",
                discount,
                limit,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.plusSeconds(7200)),
                Timestamp.from(now));
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 81001L);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 81002L);
    }

    @Test
    @DisplayName("단건과 장바구니 생성·재요청은 실제 생성마다 이벤트 한 건만 기록한다")
    void recordsCreationOnceForSingleAndCartOrders() {
        var group = create("created");
        create("created");
        assertThat(events("PaymentGroupCreated"))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.path("aggregateId").asText())
                                    .isEqualTo(group.groupNumber());
                            assertThat(event.at("/payload/orderCount").asInt()).isEqualTo(2);
                            assertThat(event.at("/payload/orders/0/productName").asText())
                                    .isEqualTo("A");
                        });
        paymentGroups.cancel(MEMBER_A, group.groupNumber());
        var command = new CreateOrderCommand(81001L, 1, "구매자", "01012345678", MEMBER_A, 10000L);
        singleCreation.create(command, "single-created");
        singleCreation.create(command, "single-created");
        assertThat(events("PaymentGroupCreated")).hasSize(2);
    }

    @Test
    @DisplayName("동시 쿠폰 수령은 회원 쿠폰과 발급 이벤트를 한 번만 기록하고 마지막 수량 소진도 유지한다")
    void concurrentCouponClaimsAndRollbackKeepOneEventPerReceipt() throws Exception {
        defineCoupon(1000, 1);
        failAfterRecording("CouponIssued");
        assertThatThrownBy(() -> coupons.claim(MEMBER_A, COUPON))
                .isInstanceOf(RuntimeException.class);
        assertThat(events("CouponIssued")).isEmpty();
        assertThat(events("CouponSoldOut")).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT issued_count FROM coupon_definition WHERE id=?",
                                Integer.class,
                                COUPON))
                .isZero();
        reset(outboxTarget());
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures =
                    java.util.stream.IntStream.range(0, 4)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        go.await();
                                                        return coupons.claim(MEMBER_A, COUPON)
                                                                .coupon()
                                                                .id();
                                                    }))
                            .toList();
            go.countDown();
            var ids = new java.util.HashSet<String>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(events("CouponIssued"))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.at("/payload/memberId").asText()).isEqualTo(MEMBER_A);
                            assertThat(event.at("/payload/name").asText()).isEqualTo("알림 쿠폰");
                        });
        assertThat(events("CouponSoldOut")).hasSize(1);
    }

    @Test
    @DisplayName("Kafka 미도달 시 보류하고 복구 후 같은 eventId와 JSON을 실제 브로커에 보낸다")
    void unavailableBrokerRetainsEventAndRecoveryDeliversSameEnvelope() {
        create("relay");
        var original = events("PaymentGroupCreated").getFirst();
        // 판매정보도 같은 Outbox에 기록되므로 이번 트랜잭션의 전체 pending을 확인한다.
        int pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE published_at IS NULL",
                        Integer.class);
        var properties = new OutboxProperties(100, Duration.ofSeconds(2));
        var failingFactory =
                new DefaultKafkaProducerFactory<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                "127.0.0.1:1",
                                "max.block.ms",
                                500,
                                "request.timeout.ms",
                                500,
                                "delivery.timeout.ms",
                                1000),
                        new StringSerializer(),
                        new StringSerializer());
        try {
            var disconnected = new KafkaTemplate<>(failingFactory);
            assertThat(new OutboxRelay(outbox, disconnected, properties, Runnable::run).relay())
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM event_outbox WHERE published_at IS NULL AND attempts=1",
                                    Integer.class))
                    .isEqualTo(1);
        } finally {
            failingFactory.destroy();
        }
        try (var consumer =
                new KafkaConsumer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                broker.getBrokersAsString(),
                                "group.id",
                                UUID.randomUUID().toString(),
                                "auto.offset.reset",
                                "earliest",
                                "enable.auto.commit",
                                false),
                        new StringDeserializer(),
                        new StringDeserializer())) {
            consumer.subscribe(List.of("commerce.payment-group.v1"));
            assertThat(new OutboxRelay(outbox, kafka, properties, Runnable::run).relay())
                    .isEqualTo(pending);
            var received =
                    new java.util.ArrayList<
                            org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (received.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(300))
                        .forEach(
                                record -> {
                                    if (record.key().equals(original.path("aggregateId").asText()))
                                        received.add(record);
                                });
            }
            assertThat(received)
                    .singleElement()
                    .satisfies(
                            record -> {
                                try {
                                    assertThat(mapper.readTree(record.value())).isEqualTo(original);
                                } catch (java.io.IOException failure) {
                                    throw new IllegalStateException(failure);
                                }
                                assertThat(
                                                new String(
                                                        record.headers()
                                                                .lastHeader("eventId")
                                                                .value(),
                                                        java.nio.charset.StandardCharsets.UTF_8))
                                        .isEqualTo(original.path("eventId").asText());
                            });
            assertThat(new OutboxRelay(outbox, kafka, properties, Runnable::run).relay()).isZero();
        }
    }
}
