package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

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
    static final String DLT = REQUESTS + ".payment.dlt";
    static final String RESULTS = "payment.refund.results.v1";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        // 다른 Postgres 테스트 컨텍스트도 캐시돼 연결을 쥐고 있다. 기본 10개면 max_connections(100)를 넘는다
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        p.add("payment.core-service-token", () -> "payment-refund-kafka-test-credential-32");
    }

    @Autowired EmbeddedKafkaBroker broker;
    @MockitoSpyBean EventOutbox outbox;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName(
            "[REF-ACCEPT-07][REF-ACCEPT-02][REF-EVENT-01] 깨진 요청은 DLT로 가고, 뒤의 요청은 접수돼 결과가 orderRef 키로 발행되며 중복 수신은 같은 결과를 다시 보낸다")
    void consumesRequestsPublishesResultsAndDeadLetters() throws Exception {
        String orderRef = newOrderRef();
        ObjectNode payload = request(orderRef, 7001L, "USER_REFUND");
        String event = envelope(orderRef, payload);

        send(orderRef, "깨진 JSON");
        send(orderRef, event);
        send(orderRef, envelope(orderRef, payload)); // Core outbox 재전달: 같은 원문, 새 eventId

        List<ConsumerRecord<String, String>> results =
                poll("payment.refund.results.v1", orderRef, 2);
        assertThat(results).extracting(ConsumerRecord::key).containsOnly(orderRef);
        JsonNode first = JSON.readTree(results.get(0).value());
        JsonNode second = JSON.readTree(results.get(1).value());
        assertThat(first.at("/payload/status").asText()).isEqualTo("FAILED");
        assertThat(first.at("/payload/failureCode").asText()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(second.get("payload")).isEqualTo(first.get("payload"));
        assertThat(refundCount(payload.get("refundRequestId").asText())).isEqualTo(1);

        ConsumerRecord<String, String> dead = poll(DLT, orderRef, 1).getFirst();
        assertThat(dead.value()).isEqualTo("깨진 JSON");
        assertThat(dead.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] 처리 중 일반 예외는 처음 포함 6번 시도한 뒤 DLT로 가고 뒤 메시지는 계속 처리된다")
    void limitedRetryThenDeadLetter() throws Exception {
        String failing = newOrderRef();
        String next = newOrderRef();
        ObjectNode request = request(failing, 7001L, "USER_REFUND");
        AtomicInteger attempts =
                failResults(failing, Integer.MAX_VALUE, new IllegalStateException("버그"));

        send(failing, envelope(failing, request));
        send(next, envelope(next, request(next, 7001L, "USER_REFUND")));

        assertThat(poll(DLT, failing, 1)).hasSize(1);
        assertThat(poll(RESULTS, next, 1)).hasSize(1);
        assertThat(attempts).hasValue(6);
        // 실패한 시도는 롤백돼 환불 행이 남지 않는다
        assertThat(refundCount(request.get("refundRequestId").asText())).isZero();
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] paymentId가 없는 요청은 결과 없이 재시도 없이 DLT로 가고 뒤 메시지는 계속 처리된다")
    void missingPaymentIdIsDeadLetteredWithoutRetry() throws Exception {
        String invalid = newOrderRef();
        String next = newOrderRef();
        ObjectNode request = request(invalid, 7001L, "USER_REFUND");
        request.remove("paymentId");

        send(invalid, envelope(invalid, request));
        send(next, envelope(next, request(next, 7001L, "USER_REFUND")));

        assertThat(poll(DLT, invalid, 1)).hasSize(1);
        assertThat(poll(RESULTS, next, 1)).hasSize(1);
        // 같은 파티션에서 앞선 메시지가 결과를 만들었다면 뒤 결과보다 먼저 보였어야 한다
        assertThat(records(RESULTS, invalid, 0, Duration.ofSeconds(1))).isEmpty();
        assertThat(refundCount(request.get("refundRequestId").asText())).isZero();
    }

    @Test
    @DisplayName("[REF-ACCEPT-07] DB 장애는 제한 횟수를 넘어도 DLT로 보내지 않고 재시도해 복구되면 처리한다")
    void databaseOutageRetriesPastLimit() throws Exception {
        String orderRef = newOrderRef();
        ObjectNode request = request(orderRef, 7001L, "USER_REFUND");
        AtomicInteger attempts = failResults(orderRef, 8, new QueryTimeoutException("DB 장애"));

        send(orderRef, envelope(orderRef, request));

        assertThat(poll(RESULTS, orderRef, 1)).hasSize(1);
        assertThat(attempts).hasValue(9);
        assertThat(records(DLT, orderRef, 1, Duration.ofSeconds(2))).isEmpty();
    }

    /** 이 주문의 결과 발행을 처음 {@code failures}번 실패시키고, 시도 횟수를 센다. 접수 트랜잭션 안의 실패다. */
    private AtomicInteger failResults(String orderRef, int failures, RuntimeException failure) {
        AtomicInteger attempts = new AtomicInteger();
        // EventOutbox.append는 MANDATORY라 스텁 호출도 트랜잭션 안에서 한다
        tx.executeWithoutResult(
                status ->
                        doAnswer(
                                        call -> {
                                            EventEnvelope<?> event = call.getArgument(1);
                                            if (orderRef.equals(event.aggregateId())
                                                    && attempts.incrementAndGet() <= failures) {
                                                throw failure;
                                            }
                                            return call.callRealMethod();
                                        })
                                .when(outbox)
                                .append(eq(RESULTS), any()));
        return attempts;
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

    private List<ConsumerRecord<String, String>> poll(String topic, String key, int count) {
        List<ConsumerRecord<String, String>> records =
                records(topic, key, count, Duration.ofSeconds(60));
        assertThat(records).hasSize(count);
        return records;
    }

    /** 다른 테스트의 메시지가 같은 토픽에 있으므로 키로 거른다. */
    private List<ConsumerRecord<String, String>> records(
            String topic, String key, int count, Duration wait) {
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
            long deadline = System.nanoTime() + wait.toNanos();
            while (records.size() < count && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record :
                        consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) {
                        records.add(record);
                    }
                }
            }
        }
        return records;
    }
}
