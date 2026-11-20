package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 broker와 PostgreSQL로 요청 소비 → 접수 → outbox 릴레이 → 결과 토픽, 중복 수신, DLT를 확인한다. 접수 서비스를 직접 부르는 테스트로는
 * 리스너·오류 처리 설정이 실제로 붙었는지 알 수 없다.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "payment.refunds.consumer-enabled=true",
            "payment.refunds.consumer.retry-interval=100ms"
        })
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
            "commerce.refund.requests.v1",
            "commerce.refund.requests.v1.payment.dlt",
            "payment.refund.results.v1"
        })
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class RefundKafkaPostgresTest extends RefundPostgresTestSupport {
    static final String SCHEMA =
            "payment_refund_kafka_" + UUID.randomUUID().toString().replace("-", "");
    static final String REQUESTS = "commerce.refund.requests.v1";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("payment.core-service-token", () -> "payment-refund-kafka-test-credential-32");
    }

    @Autowired EmbeddedKafkaBroker broker;

    @Test
    @DisplayName("[REF-ACCEPT-07][REF-ACCEPT-02][REF-EVENT-01] 깨진 요청은 DLT로 가고, 뒤의 요청은 접수돼 결과가 orderRef 키로 발행되며 중복 수신은 같은 결과를 다시 보낸다")
    void consumesRequestsPublishesResultsAndDeadLetters() throws Exception {
        String orderRef = newOrderRef();
        ObjectNode payload = request(orderRef, 7001L, "USER_REFUND");
        String event = envelope(orderRef, payload);

        send(orderRef, "깨진 JSON");
        send(orderRef, event);
        send(orderRef, envelope(orderRef, payload)); // Core outbox 재전달: 같은 원문, 새 eventId

        List<ConsumerRecord<String, String>> results = poll("payment.refund.results.v1", 2);
        assertThat(results).extracting(ConsumerRecord::key).containsOnly(orderRef);
        JsonNode first = JSON.readTree(results.get(0).value());
        JsonNode second = JSON.readTree(results.get(1).value());
        assertThat(first.at("/payload/status").asText()).isEqualTo("FAILED");
        assertThat(first.at("/payload/failureCode").asText()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(second.get("payload")).isEqualTo(first.get("payload"));
        assertThat(refundCount(payload.get("refundRequestId").asText())).isEqualTo(1);

        ConsumerRecord<String, String> dead =
                poll("commerce.refund.requests.v1.payment.dlt", 1).getFirst();
        assertThat(dead.value()).isEqualTo("깨진 JSON");
        assertThat(dead.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
    }

    static String envelope(String orderRef, JsonNode payload) throws Exception {
        ObjectNode event = JSON.createObjectNode();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "RefundRequested");
        event.put("schemaVersion", 1);
        event.put("source", "commercecore-service");
        event.put("aggregateId", orderRef);
        event.put("occurredAt", "2026-11-21T01:00:00Z");
        event.set("payload", payload);
        return JSON.writeValueAsString(event);
    }

    private void send(String key, String value) {
        try (var producer =
                new KafkaProducer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                broker.getBrokersAsString(),
                                "key.serializer",
                                StringSerializer.class,
                                "value.serializer",
                                StringSerializer.class))) {
            producer.send(new ProducerRecord<>(REQUESTS, key, value));
            producer.flush();
        }
    }

    private List<ConsumerRecord<String, String>> poll(String topic, int count) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(
                        broker.getBrokersAsString(), "reader-" + UUID.randomUUID(), "true");
        props.put("auto.offset.reset", "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<>(
                                props, new StringDeserializer(), new StringDeserializer())
                        .createConsumer()) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (records.size() < count && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        assertThat(records).hasSize(count);
        return records;
    }
}
