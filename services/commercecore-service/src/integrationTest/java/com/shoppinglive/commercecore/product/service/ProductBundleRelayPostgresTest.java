package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.product.dto.RegisterProductBundleRequest;
import com.shoppinglive.commercecore.product.dto.RegisterProductBundleRequest.InitialSku;
import com.shoppinglive.commercecore.product.support.ProductBundleOutboxAssertions;
import com.shoppinglive.common.outbox.*;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "commerce.catalog.snapshots.v1",
            "commerce.product-info.v1",
            "commerce.sales-info.v1"
        })
@SpringBootTest(
        classes = ProductBundleRegistrationPostgresTest.Configuration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            // 실제 relay 클래스를 아래에서 일반/claim 각각 호출해 전송 경로를 분리한다.
            "shoppinglive.outbox.relay.enabled=false",
            "shoppinglive.outbox.relay.excluded-topics[0]=commerce.catalog.snapshots.v1",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
        })
@DirtiesContext
@DisplayName("통합 등록 PostgreSQL Outbox의 일반·claim 릴레이 책임과 실제 판매 알림 수신")
class ProductBundleRelayPostgresTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) throws Exception {
        ProductBundleRegistrationPostgresTest.database(p);
    }

    @Autowired ProductBundleRegistrationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxMessageRepository repository;
    @Autowired OutboxProperties properties;
    @Autowired PlatformTransactionManager transactions;

    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    @org.springframework.beans.factory.annotation.Value("${spring.embedded.kafka.brokers}")
    String broker;

    @BeforeEach
    void clearOwnedRows() {
        jdbc.execute("ALTER TABLE sku DROP CONSTRAINT IF EXISTS reject_bundle");
        for (String table :
                List.of("event_outbox", "sku", "product", "product_image", "product_registration"))
            jdbc.update("DELETE FROM " + table);
    }

    @ParameterizedTest(name = "claim={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("실패 등록은 미발행이며 일반·claim relay 모두 CDC 행을 남기고 기존 판매 알림만 전송한다")
    void relaysOnlyCommittedSalesNotifications(boolean claimMode) throws Exception {
        var consumerProperties = new Properties();
        consumerProperties.put("bootstrap.servers", broker);
        consumerProperties.put("group.id", "bundle-" + UUID.randomUUID());
        consumerProperties.put("enable.auto.commit", "false");
        consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
        consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
        var leases =
                claimMode
                        ? new OutboxLeaseStore(
                                jdbc,
                                transactions,
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(10),
                                properties.excludedTopics())
                        : null;
        var relay = new OutboxRelay(repository, kafka, properties, Runnable::run, leases);
        var request =
                new RegisterProductBundleRequest(
                        "릴레이 상품",
                        "설명",
                        null,
                        List.of(new InitialSku("빨강", 100L, 2), new InitialSku("파랑", 200L, 0)));
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, 1), "png", bytes);
        try (var consumer = new KafkaConsumer<String, String>(consumerProperties)) {
            var partitions =
                    List.of(
                            new TopicPartition("commerce.catalog.snapshots.v1", 0),
                            new TopicPartition("commerce.product-info.v1", 0),
                            new TopicPartition("commerce.sales-info.v1", 0));
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            jdbc.execute("ALTER TABLE sku ADD CONSTRAINT reject_bundle CHECK(price<>200)");
            assertThatThrownBy(
                            () ->
                                    service.register(
                                            "seller",
                                            "relay",
                                            request,
                                            bytes.toByteArray(),
                                            "image/png",
                                            "relay.png"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Long.class))
                    .isZero();
            assertThat(relay.relay()).isZero();
            jdbc.execute("ALTER TABLE sku DROP CONSTRAINT reject_bundle");
            var result =
                    service.register(
                            "seller",
                            "relay",
                            request,
                            bytes.toByteArray(),
                            "image/png",
                            "relay.png");
            var skuIds = result.skus().stream().map(sku -> sku.skuId()).toList();
            ProductBundleOutboxAssertions.assertRegisteredBundle(
                    jdbc, mapper, result.productId(), skuIds);
            assertThat(relay.relay()).isEqualTo(2);
            var received = new LinkedHashMap<String, JsonNode>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received.size() < 2 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    assertThat(record.topic()).isEqualTo("commerce.sales-info.v1");
                    var envelope = mapper.readTree(record.value());
                    assertThat(envelope.path("eventType").asText()).isEqualTo("SalesRegistered");
                    assertThat(envelope.path("payload").path("productId").asLong())
                            .isEqualTo(result.productId());
                    assertThat(record.key()).isEqualTo(envelope.path("aggregateId").asText());
                    assertThat(skuIds).contains(Long.valueOf(record.key()));
                    assertThat(
                                    new String(
                                            record.headers()
                                                    .lastHeader(OutboxRelay.EVENT_ID_HEADER)
                                                    .value(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .isEqualTo(envelope.path("eventId").asText());
                    assertThat(
                                    new String(
                                            record.headers()
                                                    .lastHeader(OutboxRelay.EVENT_TYPE_HEADER)
                                                    .value(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .isEqualTo("SalesRegistered");
                    received.put(envelope.path("eventId").asText(), envelope);
                }
            }
            assertThat(received).hasSize(2);
            assertThat(
                            received.values().stream()
                                    .map(n -> n.path("payload").path("status").asText())
                                    .toList())
                    .containsExactlyInAnyOrder("ON_SALE", "SOLD_OUT");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM event_outbox WHERE topic='commerce.sales-info.v1' AND published_at IS NOT NULL",
                                    Long.class))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM event_outbox WHERE topic='commerce.catalog.snapshots.v1' AND published_at IS NULL AND attempts=0",
                                    Long.class))
                    .isEqualTo(3);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM event_outbox WHERE topic='commerce.product-info.v1'",
                                    Long.class))
                    .isZero();
            assertThat(relay.relay()).isZero();
        }
    }
}
