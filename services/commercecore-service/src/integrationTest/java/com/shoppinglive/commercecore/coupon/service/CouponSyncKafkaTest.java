package com.shoppinglive.commercecore.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.coupon.event.CouponDisplayEventPublisher;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.event.SalesInfoEventContract;
import com.shoppinglive.commercecore.sales.event.SalesInfoEventType;
import com.shoppinglive.commercecore.sales.event.SalesInfoSnapshot;
import com.shoppinglive.commercecore.sales.event.SalesInfoStatus;
import com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.common.outbox.OutboxProperties;
import com.shoppinglive.common.outbox.OutboxRelay;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(
        properties =
                "spring.kafka.bootstrap-servers=${COMMERCECORE_TEST_KAFKA_SERVERS:localhost:29092}")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_KAFKA_SERVERS", matches = ".+")
class CouponSyncKafkaTest extends CommerceCoreSecurityTestSupport {
    @Autowired CouponCreationService creation;
    @Autowired InMemoryProductLookupStub products;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxMessageRepository outbox;
    @Autowired OutboxProperties properties;

    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> outboxKafka;

    @Autowired
    @Qualifier("salesInfoKafkaTemplate")
    KafkaTemplate<String, EventEnvelope<SalesInfoSnapshot>> salesKafka;

    @DisplayName("실제 Kafka에서 별도 직렬화기를 사용한 Outbox JSON과 도메인 JSON을 수신한다")
    @Test
    void realKafkaReceivesRawOutboxJsonAndDomainJsonFromSeparateSerializers() throws Exception {
        String broker = System.getenv("COMMERCECORE_TEST_KAFKA_SERVERS");
        try (var connection = jdbc.getDataSource().getConnection()) {
            if (connection.getMetaData().getDatabaseProductName().equals("H2")
                    && jdbc.queryForList(
                                    "SELECT column_name FROM information_schema.columns WHERE table_name='COUPON_DEFINITION'")
                            .isEmpty()) {
                new ResourceDatabasePopulator(
                                new ClassPathResource("db/migration/V5__coupon_definitions.sql"),
                                new ClassPathResource(
                                        "db/migration/V17__coupon_display_snapshot_version.sql"))
                        .execute(jdbc.getDataSource());
            }
        }
        var topics = List.of(CouponDisplayEventPublisher.TOPIC, SalesInfoEventContract.TOPIC_NAME);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", broker))) {
            for (String topic : topics) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                            .all()
                            .get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException exception) {
                    if (!(exception.getCause() instanceof TopicExistsException)) throw exception;
                }
            }
        }
        long product = UUID.randomUUID().getMostSignificantBits() >>> 1;
        products.register(new ProductSnapshot(product, "직렬화 검증", null, SELLER));
        String couponId = null;
        try (var consumer =
                new KafkaConsumer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                broker,
                                "group.id",
                                "coupon-json-" + UUID.randomUUID(),
                                "enable.auto.commit",
                                false,
                                "key.deserializer",
                                StringDeserializer.class,
                                "value.deserializer",
                                StringDeserializer.class))) {
            // 이 검사에서 만든 두 토픽은 각 1개 파티션이다. 생성 직후 부분 메타데이터로 구독 토픽을 누락하지 않는다.
            var partitions = topics.stream().map(topic -> new TopicPartition(topic, 0)).toList();
            consumer.assign(partitions);
            consumer.seekToEnd(partitions);
            partitions.forEach(consumer::position);
            Instant start = Instant.now().plusSeconds(3600), end = start.plusSeconds(3600);
            couponId =
                    creation.create(SELLER, "Kafka 쿠폰", 100, 1, start, end, end, List.of(product))
                            .id();
            // 실제 저장 행을 실제 릴레이와 production template으로 전송한다. 정기 작업은 이 테스트에서 끈다.
            assertThat(new OutboxRelay(outbox, outboxKafka, properties, Runnable::run).relay())
                    .isPositive();
            var salesEvent =
                    SalesInfoEventContract.create(
                            SalesInfoEventType.SalesRegistered,
                            Instant.now(),
                            new SalesInfoSnapshot(
                                    product, product, 1, 1000, SalesInfoStatus.READY, 1, "KRW"));
            salesKafka
                    .send(SalesInfoEventContract.TOPIC_NAME, Long.toString(product), salesEvent)
                    .get(10, TimeUnit.SECONDS);
            boolean couponSeen = false, salesSeen = false;
            Instant deadline = Instant.now().plusSeconds(15);
            while ((!couponSeen || !salesSeen) && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (!record.key().equals(couponId)
                            && !record.key().equals(Long.toString(product))) continue;
                    var json = mapper.readTree(record.value());
                    assertThat(json.isObject()).isTrue();
                    assertThat(json.get("source").asText()).isEqualTo("commercecore-service");
                    assertThat(json.get("aggregateId").asText()).isEqualTo(record.key());
                    if (record.topic().equals(CouponDisplayEventPublisher.TOPIC)) {
                        couponSeen = true;
                        assertThat(json.get("eventType").asText()).isEqualTo("CouponCreated");
                        assertThat(json.at("/payload/couponId").asText()).isEqualTo(couponId);
                        assertThat(record.headers().lastHeader("eventType")).isNotNull();
                    } else {
                        salesSeen = true;
                        assertThat(json.at("/payload/salesId").asLong()).isEqualTo(product);
                    }
                }
            }
            assertThat(couponSeen).isTrue();
            assertThat(salesSeen).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM event_outbox WHERE message_key=? AND published_at IS NOT NULL",
                                    Integer.class,
                                    couponId))
                    .isEqualTo(1);
        } finally {
            if (couponId != null) {
                jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", couponId);
                jdbc.update("DELETE FROM coupon_definition WHERE id=?", couponId);
                jdbc.update("DELETE FROM event_outbox WHERE message_key=?", couponId);
            }
            products.clear();
        }
    }
}
