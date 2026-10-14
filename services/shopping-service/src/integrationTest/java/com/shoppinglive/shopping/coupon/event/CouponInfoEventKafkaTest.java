package com.shoppinglive.shopping.coupon.event;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

/** Kafka 로 받은 쿠폰 이벤트가 replica 를 거쳐 공개 쿠폰 API 에 보인다. 형식이 틀린 이벤트는 뒤 이벤트를 막지 않는다. */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class CouponInfoEventKafkaTest extends ShoppingSecuritySupport {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("형식이 틀린 이벤트는 건너뛰고, 다음 쿠폰 이벤트가 상품 쿠폰 API 에 보인다")
    void consumedCouponIsVisibleThroughPublicApi() throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            send(producer, "broken", "{not json");
            send(producer, "kafka-coupon", event("kafka-coupon"));
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                mvc.perform(get("/v1/products/701/coupons"))
                                        .andExpect(status().isOk())
                                        .andExpect(
                                                jsonPath("$.data[0].couponId")
                                                        .value("kafka-coupon"))
                                        .andExpect(jsonPath("$.data[0].fixedDiscount").value(1000))
                                        .andExpect(jsonPath("$.data[0].soldOut").value(false)));
    }

    private Map<String, Object> producerProperties() {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    private static void send(KafkaProducer<String, String> producer, String key, String json)
            throws Exception {
        producer.send(new ProducerRecord<>(CouponInfoEventContract.TOPIC_NAME, key, json))
                .get(10, TimeUnit.SECONDS);
    }

    private static String event(String couponId) {
        return """
               {"eventId":"74b623b8-90ee-4f65-919c-d9ad2ea46fc1","eventType":"CouponCreated",
                "schemaVersion":1,"source":"commerce-service","aggregateId":"%1$s",
                "occurredAt":"%2$s","payload":{"couponId":"%1$s","snapshotVersion":1,
                "name":"카프카 쿠폰","fixedDiscount":1000,"productIds":[701],
                "startsAt":"%2$s","endsAt":"%3$s","expiresAt":"%4$s","soldOut":false}}"""
                .formatted(
                        couponId,
                        NOW.minusSeconds(60),
                        NOW.plus(1, ChronoUnit.DAYS),
                        NOW.plus(2, ChronoUnit.DAYS));
    }
}
