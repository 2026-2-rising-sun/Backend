package com.shoppinglive.catalog.product.event;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.*;
import com.shoppinglive.catalog.product.repository.ProductRepository;
import com.shoppinglive.catalog.security.CatalogSecuritySupport;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            "spring.datasource.url=jdbc:h2:mem:product_info_kafka;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
@EmbeddedKafka(
        partitions = 1,
        topics = "commerce.product-info.v1",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ProductInfoEventKafkaTest extends CatalogSecuritySupport {
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ProductRepository products;

    @Test
    @DisplayName("실제 Kafka에서 version 0 등록과 수정 snapshot을 받고 중복·역순은 무시한다")
    void appliesInitialAndUpdatedSnapshotsThroughBroker() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (var producer = new KafkaProducer<String, String>(config)) {
            send(producer, 0, "처음");
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                var found = products.findById(7L);
                                assertThat(found).isPresent();
                                var p = found.orElseThrow();
                                assertThat(p.getVersion()).isZero();
                                assertThat(p.getName()).isEqualTo("처음");
                            });
            send(producer, 4, "수정");
            send(producer, 4, "중복");
            send(producer, 0, "역순");
            // 뒤쪽 별도 상품이 반영되면 같은 partition의 앞 이벤트가 모두 처리되었다.
            producer.send(
                            new ProducerRecord<>(
                                    ProductInfoEventMessage.TOPIC, "8", event(8, 0, "마지막")))
                    .get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(products.existsById(8L)).isTrue());
            var current = products.findById(7L).orElseThrow();
            assertThat(current.getVersion()).isEqualTo(4);
            assertThat(current.getName()).isEqualTo("수정");
            assertThat(current.getMainImageUrl())
                    .isEqualTo("/api/commercecore/v1/product-images/10");
        }
    }

    private void send(KafkaProducer<String, String> producer, long version, String name)
            throws Exception {
        producer.send(
                        new ProducerRecord<>(
                                ProductInfoEventMessage.TOPIC, "7", event(7, version, name)))
                .get(10, TimeUnit.SECONDS);
    }

    private String event(long id, long version, String name) {
        var root = new ObjectMapper().createObjectNode();
        root.put("eventId", UUID.randomUUID().toString())
                .put("eventType", "PRODUCT_INFO_CHANGED")
                .put("schemaVersion", 1)
                .put("source", "commercecore-service")
                .put("aggregateId", Long.toString(id))
                .put("occurredAt", "2026-11-08T00:00:01Z");
        root.putObject("payload")
                .put("productId", id)
                .put("name", name)
                .put("description", "설명")
                .put("category", "FASHION")
                .put("mainImageId", 10)
                .put("mainImageUrl", "/api/commercecore/v1/product-images/10")
                .put("sellerId", "seller")
                .put("version", version)
                .put("createdAt", "2026-11-08T00:00:00Z")
                .put("updatedAt", "2026-11-08T00:00:00Z");
        return root.toString();
    }
}
