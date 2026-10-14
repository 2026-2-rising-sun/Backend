package com.shoppinglive.shopping.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.shopping.sales.repository.ProductSalesRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실패 분류를 실제 Kafka 로 확인한다. DB 장애는 끝까지 기다린다. 형식 오류와 계속 실패하는 메시지는 DLT 로 보내고 다음 이벤트로 넘어간다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            "shopping.events.consumer.retry-interval=10ms"
        })
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SalesInfoEventKafkaTest extends ShoppingSecuritySupport {

    private static final int FAILURES_BEFORE_RECOVERY = 11;
    private static final long FIRST_PRODUCT = 501L;
    private static final long SECOND_PRODUCT = 502L;
    private static final long THIRD_PRODUCT = 503L;
    private static final long FOURTH_PRODUCT = 504L;
    private static final long BUGGY_PRODUCT = 505L;
    private static final long FIFTH_PRODUCT = 506L;
    private static final int CLASS_CAST_FAILURES = 3;

    @Autowired EmbeddedKafkaBroker broker;

    @MockitoSpyBean ProductSalesRepository repository;

    @Test
    @DisplayName("DB 장애가 10회를 넘게 나도 이벤트를 건너뛰지 않고, 복구 뒤 그 이벤트와 다음 이벤트가 모두 반영된다")
    void eventIsAppliedAfterMoreThanTenDbFailuresAndNextEventFollows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() <= FAILURES_BEFORE_RECOVERY) {
                                throw new DataAccessResourceFailureException("db down");
                            }
                            return invocation.callRealMethod();
                        })
                .when(repository)
                .apply(any(), anyLong());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, event(FIRST_PRODUCT));
            send(producer, event(SECOND_PRODUCT));
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                repository.findByProductIds(
                                                        List.of(FIRST_PRODUCT, SECOND_PRODUCT)))
                                        .hasSize(2));
    }

    @Test
    @DisplayName("ClassCastException 이 여러 번 나도 이벤트를 건너뛰지 않고, 복구 뒤 그 이벤트가 반영된다")
    void eventIsAppliedAfterClassCastFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() <= CLASS_CAST_FAILURES) {
                                throw new ClassCastException("unexpected type");
                            }
                            return invocation.callRealMethod();
                        })
                .when(repository)
                .apply(any(), anyLong());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, event(THIRD_PRODUCT));
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(repository.findByProductIds(List.of(THIRD_PRODUCT)))
                                        .hasSize(1));
    }

    @Test
    @DisplayName("형식이 틀린 메시지는 재시도 없이 원문 그대로 DLT 로 가고, 다음 이벤트가 반영된다")
    void invalidMessageGoesToDltAndNextEventFollows() throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, "{broken-json");
            send(producer, event(FOURTH_PRODUCT));
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(repository.findByProductIds(List.of(FOURTH_PRODUCT)))
                                        .hasSize(1));
        assertDltHas("{broken-json"::equals);
    }

    @Test
    @DisplayName("DB 장애가 아닌 예외가 계속 나면 몇 번 재시도한 뒤 DLT 로 보내고, 다음 이벤트가 반영된다")
    void repeatedBugGoesToDltAfterRetriesAndNextEventFollows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            if (invocation
                                            .<com.shoppinglive.shopping.client.commerce.SalesInfo>
                                                    getArgument(0)
                                            .productId()
                                    == BUGGY_PRODUCT) {
                                calls.incrementAndGet();
                                throw new IllegalStateException("bug");
                            }
                            return invocation.callRealMethod();
                        })
                .when(repository)
                .apply(any(), anyLong());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, event(BUGGY_PRODUCT));
            send(producer, event(FIFTH_PRODUCT));
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(repository.findByProductIds(List.of(FIFTH_PRODUCT)))
                                        .hasSize(1));
        // 첫 처리 1번 + 재시도 5번
        assertThat(calls).hasValue(6);
        assertDltHas(value -> value.contains("\"productId\":" + BUGGY_PRODUCT));
    }

    /** DLT 를 처음부터 읽어, 조건에 맞는 원문이 나올 때까지 기다린다. */
    private void assertDltHas(Predicate<String> expected) {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(
                        broker.getBrokersAsString(), "dlt-reader-" + UUID.randomUUID(), "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<>(
                                props, new StringDeserializer(), new StringDeserializer())
                        .createConsumer()) {
            consumer.subscribe(List.of(SalesInfoEventContract.TOPIC_NAME + ".shopping.dlt"));
            List<String> values = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20))
                    .until(
                            () -> {
                                consumer.poll(Duration.ofMillis(200))
                                        .forEach(record -> values.add(record.value()));
                                return values.stream().anyMatch(expected);
                            });
        }
    }

    private Map<String, Object> producerProperties() {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    private static void send(KafkaProducer<String, String> producer, String json) throws Exception {
        producer.send(new ProducerRecord<>(SalesInfoEventContract.TOPIC_NAME, "9001", json))
                .get(10, TimeUnit.SECONDS);
    }

    private static String event(long productId) throws Exception {
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper.writeValueAsString(
                new EventEnvelope<>(
                        "74b623b8-90ee-4f65-919c-d9ad2ea46fb2",
                        "PriceChanged",
                        1,
                        "commerce-service",
                        "9001",
                        Instant.parse("2026-10-09T06:00:00Z"),
                        Map.of(
                                "salesId",
                                9001L,
                                "productId",
                                productId,
                                "snapshotVersion",
                                1,
                                "price",
                                27000,
                                "status",
                                "ON_SALE",
                                "available",
                                20,
                                "currency",
                                "KRW")));
    }
}
