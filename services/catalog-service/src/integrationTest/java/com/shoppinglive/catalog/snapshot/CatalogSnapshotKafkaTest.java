package com.shoppinglive.catalog.snapshot;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.catalog.security.CatalogSecuritySupport;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            "catalog.legacy-events.enabled=false",
            "catalog.snapshots.group-id=catalog-b-kafka-test",
            "spring.datasource.url=jdbc:h2:mem:catalog_snapshot_kafka;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
@EmbeddedKafka(
        partitions = 1,
        topics = "commerce.catalog.snapshots.v1",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class CatalogSnapshotKafkaTest extends CatalogSecuritySupport {
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("공통 Kafka 리스너가 재고·SKU·상품 역순과 재전송을 독립 handler로 반영한다")
    void dispatchesAllThreeTypesThroughBroker() throws Exception {
        var mapper = new ObjectMapper();
        var directory = Path.of("../../docs/worklog/plans/active/2026-11-event-contracts/examples");
        Map<String, Object> config =
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        broker.getBrokersAsString(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(config)) {
            for (String type :
                    List.of(
                            "InventorySnapshotChanged",
                            "SkuSnapshotChanged",
                            "ProductSnapshotChanged",
                            "InventorySnapshotChanged")) {
                var json = Files.readString(directory.resolve(type + ".json"));
                var envelope = mapper.readTree(json);
                producer.send(
                                new ProducerRecord<>(
                                        "commerce.catalog.snapshots.v1",
                                        envelope.path("aggregateId").asText(),
                                        json))
                        .get(10, TimeUnit.SECONDS);
            }
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM product WHERE id=7",
                                                        Long.class))
                                        .isEqualTo(1);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT name FROM product WHERE id=7",
                                                        String.class))
                                        .isEqualTo("상품");
                                assertThat(
                                                jdbc.queryForMap(
                                                        "SELECT price,available,status FROM product_sales WHERE sales_id=71"))
                                        .containsEntry("price", 4000L)
                                        .containsEntry("available", 8)
                                        .containsEntry("status", "ON_SALE");
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM catalog_snapshot_receipt",
                                                        Long.class))
                                        .isEqualTo(3);
                            });
        }
    }
}
