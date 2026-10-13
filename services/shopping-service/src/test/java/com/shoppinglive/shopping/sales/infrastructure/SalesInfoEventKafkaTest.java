package com.shoppinglive.shopping.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shoppinglive.contracts.events.SalesInfoEvent;
import com.shoppinglive.contracts.events.SalesInfoEventContract;
import com.shoppinglive.contracts.events.SalesInfoEventType;
import com.shoppinglive.contracts.events.SalesInfoSnapshot;
import com.shoppinglive.contracts.events.SalesInfoStatus;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** DB 장애가 10회를 넘어도 offset 을 넘기지 않아, 복구 뒤 같은 이벤트가 반영되고 다음 이벤트도 이어진다. */
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=true",
    "shopping.sales.consumer.retry-interval=10ms"})
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class SalesInfoEventKafkaTest extends ShoppingSecuritySupport {

    private static final int FAILURES_BEFORE_RECOVERY = 11;
    private static final long FIRST_PRODUCT = 501L;
    private static final long SECOND_PRODUCT = 502L;
    private static final long THIRD_PRODUCT = 503L;
    private static final int CLASS_CAST_FAILURES = 3;

    @Autowired
    EmbeddedKafkaBroker broker;

    @MockitoSpyBean
    ProductSalesRepository repository;

    @Test
    @DisplayName("DB 장애가 10회를 넘게 나도 이벤트를 건너뛰지 않고, 복구 뒤 그 이벤트와 다음 이벤트가 모두 반영된다")
    void eventIsAppliedAfterMoreThanTenDbFailuresAndNextEventFollows() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= FAILURES_BEFORE_RECOVERY) {
                throw new DataAccessResourceFailureException("db down");
            }
            return invocation.callRealMethod();
        }).when(repository).apply(any(), anyLong());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, event(FIRST_PRODUCT));
            send(producer, event(SECOND_PRODUCT));
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(repository.findByProductIds(List.of(FIRST_PRODUCT, SECOND_PRODUCT))).hasSize(2));
    }

    @Test
    @DisplayName("ClassCastException 이 여러 번 나도 이벤트를 건너뛰지 않고, 복구 뒤 그 이벤트가 반영된다")
    void eventIsAppliedAfterClassCastFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= CLASS_CAST_FAILURES) {
                throw new ClassCastException("unexpected type");
            }
            return invocation.callRealMethod();
        }).when(repository).apply(any(), anyLong());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, event(THIRD_PRODUCT));
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(repository.findByProductIds(List.of(THIRD_PRODUCT))).hasSize(1));
    }

    private Map<String, Object> producerProperties() {
        return Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    private static void send(KafkaProducer<String, String> producer, String json) throws Exception {
        producer.send(new ProducerRecord<>(SalesInfoEventContract.TOPIC_NAME, "9001", json)).get(10, TimeUnit.SECONDS);
    }

    private static String event(long productId) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper.writeValueAsString(new SalesInfoEvent(
            "74b623b8-90ee-4f65-919c-d9ad2ea46fb2", SalesInfoEventType.PriceChanged, 1,
            Instant.parse("2026-10-09T06:00:00Z"),
            new SalesInfoSnapshot(9001, productId, 1, 27000, SalesInfoStatus.ON_SALE, 20, "KRW")));
    }
}
