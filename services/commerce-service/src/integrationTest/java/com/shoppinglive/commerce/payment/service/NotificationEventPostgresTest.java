package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.coupon.service.CouponClaimService;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.service.OrderCreationService;
import com.shoppinglive.commerce.order.service.PaymentGroupService;
import com.shoppinglive.commerce.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 PostgreSQL 마이그레이션과 별도 스키마에서 업무 변경·이벤트 기록을 함께 검증한다. */
@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
@EmbeddedKafka(
        partitions = 1,
        topics = {"commerce.payment-group.v1", "commerce.coupon-info.v1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationEventPostgresTest extends RefundTestSupport {
    private static final String SCHEMA =
            "notification_events_" + UUID.randomUUID().toString().replace("-", "");
    private static final String COUPON = "event-coupon";
    @Autowired ObjectMapper mapper;
    @Autowired RefundIntakeService refunds;
    @Autowired RefundExecutionService executions;
    @Autowired CouponClaimService coupons;
    @Autowired OrderCreationService singleCreation;
    @Autowired DevPaymentScenarioRegistry scenarios;
    @Autowired PaymentRecoveryStore recovery;
    @MockitoSpyBean EventOutbox eventOutbox;
    @Autowired OutboxMessageRepository outbox;
    @Autowired EmbeddedKafkaBroker broker;

    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    private static String databaseUrl() {
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", NotificationEventPostgresTest::databaseUrl);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add(
                "spring.datasource.username",
                () -> System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"));
        properties.add(
                "spring.datasource.password",
                () -> System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @AfterAll
    static void dropOwnedSchema() {
        var source =
                new DriverManagerDataSource(
                        databaseUrl(),
                        System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
                        System.getenv()
                                .getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        Flyway.configure()
                .dataSource(source)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .cleanDisabled(false)
                .load()
                .clean();
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
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status().name())
                .isEqualTo("PENDING_PAYMENT");
        reset(outboxTarget());
        assertThat(paymentGroups.expire(id)).isTrue();
        assertThat(events("OrderExpired")).hasSize(1);
    }

    @Test
    @DisplayName("실행 권한을 잃은 결제는 발행하지 않고 동시 복구는 성공 이벤트 한 건만 기록한다")
    void staleLeaseAndConcurrentRecoveryDoNotDuplicatePaymentEvents() throws Exception {
        var group = create("lease");
        var payment = paymentGroups.start(MEMBER_A, group.groupNumber(), "lease-pay");
        var stale = recovery.claim(payment.getId(), Duration.ofMinutes(1)).orElseThrow();
        jdbc.update(
                "UPDATE payment_attempt SET lease_token=? WHERE id=?",
                UUID.randomUUID().toString(),
                payment.getId());
        assertThat(
                        paymentGroups.applyOutcome(
                                stale,
                                com.shoppinglive.commerce.payment.entity.PaymentStatus.SUCCESS))
                .isFalse();
        assertThat(events("PaymentSucceeded")).isEmpty();
        jdbc.update(
                "UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                payment.getId());
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures =
                    java.util.stream.IntStream.range(0, 4)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        go.await();
                                                        return paymentGroups.resolve(
                                                                payment.getId());
                                                    }))
                            .toList();
            go.countDown();
            int resolved = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) resolved++;
            assertThat(resolved).isEqualTo(1);
        }
        assertThat(events("PaymentSucceeded")).hasSize(1);
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
    @DisplayName("미확정 결제에는 결과 이벤트가 없고 복구 성공을 중복 발행하지 않는다")
    void unknownThenSuccessRecordsOnlyOneConfirmedResult() {
        var group = create("unknown");
        scenarios.set(group.groupNumber(), PaymentScenario.SUCCESS_RESPONSE_LOST);
        var payment = paymentGroups.start(MEMBER_A, group.groupNumber(), "payment");
        assertThat(paymentGroups.resolve(payment.getId())).isFalse();
        assertThat(events("PaymentSucceeded")).isEmpty();
        assertThat(events("PaymentFailed")).isEmpty();
        jdbc.update(
                "UPDATE payment_attempt SET scheduled_resolve_at=CURRENT_TIMESTAMP WHERE id=?",
                payment.getId());
        assertThat(paymentGroups.resolve(payment.getId())).isTrue();
        assertThat(paymentGroups.resolve(payment.getId())).isFalse();
        assertThat(events("PaymentSucceeded"))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.at("/payload/paidAmount").asLong()).isEqualTo(25000L);
                            assertThat(event.at("/payload/paymentId").asLong())
                                    .isEqualTo(payment.getId());
                        });
        assertThat(events("PaymentFailed")).isEmpty();
    }

    @Test
    @DisplayName("실패·취소·만료의 실제 전이에만 이벤트를 한 건씩 기록한다")
    void recordsFailureCancellationAndExpiryOnceEach() {
        var failed = create("failed");
        scenarios.set(failed.groupNumber(), PaymentScenario.INSTANT_FAIL);
        var payment = paymentGroups.start(MEMBER_A, failed.groupNumber(), "failed-pay");
        assertThat(paymentGroups.resolve(payment.getId())).isTrue();
        paymentGroups.resolve(payment.getId());
        assertThat(events("PaymentFailed"))
                .singleElement()
                .satisfies(
                        event ->
                                assertThat(event.at("/payload/failureCode").asText())
                                        .isEqualTo("REJECTED"));
        var cancelled = create("cancelled");
        paymentGroups.cancel(MEMBER_A, cancelled.groupNumber());
        paymentGroups.cancel(MEMBER_A, cancelled.groupNumber());
        assertThat(events("OrderCancelled")).hasSize(1);
        var expired = create("expired");
        long id =
                groups.findByGroupNumberAndMemberId(expired.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        assertThat(paymentGroups.expire(id)).isFalse();
        jdbc.update(
                "UPDATE payment_group SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                id);
        assertThat(paymentGroups.expire(id)).isTrue();
        assertThat(paymentGroups.expire(id)).isFalse();
        assertThat(events("OrderExpired")).hasSize(1);
    }

    @Test
    @DisplayName("전액 할인 결제도 성공 이벤트 한 건과 0원 결제액을 기록한다")
    void zeroPaymentPublishesOnceWithDiscountedAmounts() {
        defineCoupon(25000, 2);
        coupons.claim(MEMBER_A, COUPON);
        var created =
                paymentGroups
                        .create(
                                MEMBER_A,
                                List.of(
                                        new PaymentGroupService.Selection(
                                                a.getId(), a.getVersion()),
                                        new PaymentGroupService.Selection(
                                                b.getId(), b.getVersion())),
                                "구매자",
                                "01012345678",
                                25000L,
                                COUPON,
                                "zero")
                        .group();
        paymentGroups.start(MEMBER_A, created.groupNumber(), "zero-pay");
        paymentGroups.start(MEMBER_A, created.groupNumber(), "zero-pay");
        assertThat(events("PaymentSucceeded"))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.at("/payload/paidAmount").asLong()).isZero();

                            assertThat(event.at("/payload/orders/0/amount").asLong()).isZero();
                        });
    }

    @Test
    @DisplayName("부분 환불 요청마다 금액과 성공 누적액을 기록하고 재실행은 발행하지 않는다")
    void partialRefundsPublishPerSuccessfulRequestWithCumulativeAmount() {
        var group = createAndPayWithCoupon();
        var first =
                refunds.request(MEMBER_A, group.groupNumber(), "refund-a", List.of(a.getId()))
                        .refund();
        assertThat(executions.execute(first.id())).isTrue();
        assertThat(executions.execute(first.id())).isFalse();
        var firstEvent = events("RefundCompleted").getFirst();
        assertThat(firstEvent.at("/payload/refundRequestId").asLong()).isEqualTo(first.id());
        assertThat(firstEvent.at("/payload/refundedAmount").asLong())
                .isEqualTo(first.refundAmount());
        assertThat(firstEvent.at("/payload/cumulativeRefundAmount").asLong())
                .isEqualTo(first.refundAmount());

        var second =
                refunds.request(MEMBER_A, group.groupNumber(), "refund-b", List.of(b.getId()))
                        .refund();
        assertThat(executions.execute(second.id())).isTrue();
        assertThat(events("RefundCompleted")).hasSize(2);
        var secondEvent = events("RefundCompleted").getLast();
        assertThat(secondEvent.at("/payload/cumulativeRefundAmount").asLong())
                .isEqualTo(group.payableAmount());
    }

    @Test
    @DisplayName("이벤트 기록 이후 오류가 나면 생성·결제·환불 상태와 Outbox를 함께 롤백한다")
    void outboxFailureRollsBackBusinessChangesAndAllowsRecovery() {
        failAfterRecording("PaymentGroupCreated");
        assertThatThrownBy(() -> create("rollback-created")).isInstanceOf(RuntimeException.class);
        assertThat(groups.count()).isZero();
        assertThat(orders.count()).isZero();
        assertThat(events("PaymentGroupCreated")).isEmpty();
        reset(outboxTarget());
        var group = create("rollback-pay");
        var payment = paymentGroups.start(MEMBER_A, group.groupNumber(), "payment");
        failAfterRecording("PaymentSucceeded");
        assertThatThrownBy(() -> paymentGroups.resolve(payment.getId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status().name())
                .isEqualTo("PAYMENT_CONFIRMING");
        assertThat(events("PaymentSucceeded")).isEmpty();
        reset(outboxTarget());
        jdbc.update(
                "UPDATE payment_attempt SET scheduled_resolve_at=CURRENT_TIMESTAMP WHERE id=?",
                payment.getId());
        assertThat(paymentGroups.resolve(payment.getId())).isTrue();
        var request =
                refunds.request(MEMBER_A, group.groupNumber(), "refund", List.of(a.getId()))
                        .refund();
        failAfterRecording("RefundCompleted");
        assertThatThrownBy(() -> executions.execute(request.id()))
                .isInstanceOf(RuntimeException.class);
        assertThat(events("RefundCompleted")).isEmpty();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status().name())
                .isEqualTo("PROCESSING");
        reset(outboxTarget());
        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(events("RefundCompleted")).hasSize(1);
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
