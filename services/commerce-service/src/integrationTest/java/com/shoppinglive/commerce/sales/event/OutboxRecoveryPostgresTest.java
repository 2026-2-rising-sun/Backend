package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.shoppinglive.common.outbox.OutboxLeaseStore;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.common.outbox.OutboxProperties;
import com.shoppinglive.common.outbox.OutboxRelay;
import java.time.Duration;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxRecoveryPostgresTest {
    private final String schema =
            "outbox_recovery_" + UUID.randomUUID().toString().replace("-", "");
    private final String topic = "outbox-recovery-" + UUID.randomUUID();
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private OutboxLeaseStore leases;
    private EmbeddedKafkaKraftBroker broker;
    private DefaultKafkaProducerFactory<String, String> producer;
    private KafkaTemplate<String, String> kafka;

    @BeforeAll
    void startOwnedEnvironment() {
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        System.getenv("COMMERCE_TEST_POSTGRES_USER"),
                        System.getenv("COMMERCE_TEST_POSTGRES_PASSWORD"));
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
        leases = store();
        broker = new EmbeddedKafkaKraftBroker(1, 1, topic);
        broker.brokerListProperty("shoppinglive.test.recovery-kafka");
        broker.afterPropertiesSet();
        producer =
                new DefaultKafkaProducerFactory<>(
                        Map.of("bootstrap.servers", broker.getBrokersAsString()),
                        new StringSerializer(),
                        new StringSerializer());
        kafka = new KafkaTemplate<>(producer);
    }

    @BeforeEach
    void clearOwnedRows() {
        jdbc.update("DELETE FROM event_outbox");
    }

    @AfterAll
    void stopOwnedEnvironment() {
        if (producer != null) producer.destroy();
        if (broker != null) broker.destroy();
        if (source != null)
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .cleanDisabled(false)
                    .load()
                    .clean();
    }

    private OutboxLeaseStore store() {
        return new OutboxLeaseStore(
                jdbc,
                new DataSourceTransactionManager(source),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofSeconds(4));
    }

    private String append(String key) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO event_outbox(event_id,event_type,topic,message_key,payload,created_at,attempts)
                VALUES (?,'InventoryChanged',?,?,?,CURRENT_TIMESTAMP,0)
                """,
                id,
                topic,
                key,
                "{\"eventId\":\"" + id + "\",\"available\":3}");
        return id;
    }

    @Test
    @DisplayName("두 worker는 같은 키의 선행 이벤트만 확보하고 서로 다른 판매는 병렬 처리한다")
    void competingWorkersClaimDifferentKeysWithoutOvertaking() throws Exception {
        String first = append("sale-a");
        append("sale-a");
        append("sale-b");
        var other = store();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return leases.claim();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return other.claim();
                            });
            start.countDown();
            var claims = List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
            assertThat(claims)
                    .extracting(OutboxLeaseStore.Claim::messageKey)
                    .containsExactlyInAnyOrder("sale-a", "sale-b");
            assertThat(claims).extracting(OutboxLeaseStore.Claim::id).doesNotHaveDuplicates();
            assertThat(
                            claims.stream()
                                    .filter(c -> c.messageKey().equals("sale-a"))
                                    .findFirst()
                                    .orElseThrow()
                                    .eventId())
                    .isEqualTo(first);
            assertThat(leases.claim()).isNull();
            claims.forEach(c -> assertThat(leases.published(c)).isTrue());
            assertThat(leases.claim().messageKey()).isEqualTo("sale-a");
        }
    }

    @Test
    @DisplayName("claim 후 종료된 worker의 lease를 회수하고 이전 token의 완료·실패 갱신을 거절한다")
    void expiredLeaseCanBeReclaimedAndFencesOldWorker() {
        append("sale-a");
        var old = leases.claim();
        jdbc.update(
                "UPDATE event_outbox SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",
                old.id());
        assertThat(leases.published(old)).isFalse();
        var recovered = store().claim();
        assertThat(recovered.eventId()).isEqualTo(old.eventId());
        assertThat(recovered.payload()).isEqualTo(old.payload());
        assertThat(recovered.token()).isNotEqualTo(old.token());
        assertThat(leases.published(old)).isFalse();
        assertThat(leases.failed(old, "이전 worker의 늦은 응답")).isFalse();
        assertThat(leases.published(recovered)).isTrue();
    }

    @Test
    @DisplayName("전송 실패는 같은 이벤트를 보존하고 지수 backoff 중 같은 판매의 후속 발행을 막는다")
    void retryBackoffPreservesPayloadAndDoesNotBlockOtherKeys() {
        String id = append("sale-a");
        append("sale-a");
        var failed = leases.claim();
        assertThat(leases.failed(failed, "검증용 브로커 장애")).isTrue();
        assertThat(leases.claim()).isNull();
        append("sale-b");
        var other = leases.claim();
        assertThat(other.messageKey()).isEqualTo("sale-b");
        assertThat(leases.published(other)).isTrue();
        for (int attempt = 1; attempt <= 4; attempt++) {
            jdbc.update(
                    "UPDATE event_outbox SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE event_id=?",
                    id);
            var retry = leases.claim();
            assertThat(retry.eventId()).isEqualTo(failed.eventId());
            assertThat(retry.payload()).isEqualTo(failed.payload());
            assertThat(retry.attempts()).isEqualTo(attempt);
            assertThat(leases.failed(retry, "재시도")).isTrue();
            double seconds =
                    jdbc.queryForObject(
                            "SELECT EXTRACT(EPOCH FROM (next_attempt_at-CURRENT_TIMESTAMP)) FROM event_outbox WHERE event_id=?",
                            Double.class,
                            id);
            assertThat(seconds).isBetween(attempt == 1 ? 1.0 : 3.0, attempt == 1 ? 2.1 : 4.1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE published_at IS NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("전송 장애 후 재기동한 relay는 저장된 JSON을 실제 Kafka에 보내며 ACK 후 미기록도 재전송한다")
    void restartedRelayDeliversPendingAndReplaysAfterMissingAcknowledgementWrite()
            throws Exception {
        String id = append("sale-a");
        var unavailableFactory =
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
            assertThat(relay(new KafkaTemplate<>(unavailableFactory)).relay()).isZero();
        } finally {
            unavailableFactory.destroy();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at IS NULL FROM event_outbox WHERE event_id=?",
                                Boolean.class,
                                id))
                .isTrue();
        jdbc.update(
                "UPDATE event_outbox SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        try (var consumer = consumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, topic);
            // ACK를 받았지만 완료 기록 전에 종료되는 상황을 재현한다.
            var abandoned = leases.claim();
            kafka.send(topic, abandoned.messageKey(), abandoned.payload()).get(5, TimeUnit.SECONDS);
            assertThat(
                            org.springframework.kafka.test.utils.KafkaTestUtils.getSingleRecord(
                                            consumer, topic)
                                    .value())
                    .isEqualTo(abandoned.payload());
            jdbc.update(
                    "UPDATE event_outbox SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
            var restarted = relay(kafka);
            assertThat(restarted.relay()).isEqualTo(1);
            var replay =
                    org.springframework.kafka.test.utils.KafkaTestUtils.getSingleRecord(
                            consumer, topic);
            assertThat(replay.value()).isEqualTo(abandoned.payload());
            assertThat(replay.key()).isEqualTo("sale-a");
            assertThat(
                            new String(
                                    replay.headers().lastHeader("eventId").value(),
                                    java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo(id);
            assertThat(replay.headers().lastHeader("__TypeId__")).isNull();
            assertThat(restarted.relay()).isZero();
        }
    }

    private OutboxRelay relay(KafkaTemplate<String, String> template) {
        return new OutboxRelay(
                mock(OutboxMessageRepository.class),
                template,
                new OutboxProperties(10, Duration.ofSeconds(1)),
                Runnable::run,
                store());
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(
                Map.of(
                        "bootstrap.servers",
                        broker.getBrokersAsString(),
                        "group.id",
                        UUID.randomUUID().toString(),
                        "auto.offset.reset",
                        "latest",
                        "key.deserializer",
                        StringDeserializer.class,
                        "value.deserializer",
                        StringDeserializer.class));
    }
}
