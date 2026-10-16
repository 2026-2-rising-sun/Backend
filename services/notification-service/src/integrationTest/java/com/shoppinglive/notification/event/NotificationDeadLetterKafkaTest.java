package com.shoppinglive.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * 실제 broker 를 띄워 "유실되지 않고 보관함에 모이는가" 를 확인한다.
 *
 * <p>다른 테스트는 리스너를 직접 불러서 검증한다. 그 방식으로는 <b>설정이 실제로 적용되는지</b>를 알 수 없다. 같은 레포에서 Commerce 의 이벤트 발행이 모든
 * 요청에서 실패하고 있었는데 테스트가 못 잡은 일(#244)이 있었고, 원인 중 하나가 실제 broker 로 보내는 테스트가 없었던 것이다.
 */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            // 이 테스트는 broker 동작을 보므로 DB 는 늘 전용 H2 를 쓴다. 드라이버와 계정까지
            // 함께 고정하지 않으면, 다른 테스트를 실제 PostgreSQL 로 돌릴 때 주는 환경변수가
            // 드라이버만 바꿔 버려 H2 주소에 PostgreSQL 드라이버가 붙는다.
            "spring.datasource.url=jdbc:h2:mem:notification_dead_letter;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
        })
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
            "live.broadcast.v1",
            "live.broadcast.v1.notification.dlt",
            "commerce.sales-info.v1",
            "commerce.sales-info.v1.notification.dlt"
        })
@DisplayName("실패한 이벤트 보관함")
class NotificationDeadLetterKafkaTest extends NotificationSecuritySupport {

    private static final String TOPIC = "live.broadcast.v1";
    private static final String DEAD_LETTER_TOPIC = TOPIC + ".notification.dlt";
    private static final long BROADCAST = 42L;

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired BroadcastScheduleRepository schedules;

    @Test
    @DisplayName("해석할 수 없는 메시지는 보관함에 그대로 모이고, 뒤의 정상 메시지는 계속 처리된다")
    void deadLettersUnreadableMessageAndKeepsConsuming() {
        send("깨진 JSON");
        send(scheduled());

        // 뒤의 정상 메시지가 처리됐다는 것은 컨슈머가 깨진 메시지에 갇히지 않았다는 뜻이다.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(schedules.findById(BROADCAST))
                                        .get()
                                        .extracting("title")
                                        .isEqualTo("가을 신상 라이브"));

        ConsumerRecord<String, String> deadLettered = pollDeadLetter();
        // 원본 전문이 그대로 보관된다. 그래야 원인을 보고 되돌릴 수 있다.
        assertThat(deadLettered.value()).isEqualTo("깨진 JSON");
        // 어느 토픽의 몇 번째 메시지였는지가 헤더에 함께 담긴다.
        assertThat(deadLettered.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
    }

    private static String scheduled() {
        return "{\"eventId\":\"74b623b8-90ee-4f65-919c-d9ad2ea46fb2\","
                + "\"eventType\":\"LiveBroadcastScheduled\",\"schemaVersion\":1,"
                + "\"source\":\"live-service\",\"aggregateId\":\""
                + BROADCAST
                + "\",\"occurredAt\":\"2026-10-15T00:00:00Z\",\"payload\":{\"broadcastId\":"
                + BROADCAST
                + ",\"broadcastVersion\":1,\"title\":\"가을 신상 라이브\","
                + "\"scheduledAt\":\"2026-10-20T12:00:00Z\"}}";
    }

    private void send(String value) {
        try (var producer =
                new KafkaProducer<String, String>(
                        Map.of(
                                "bootstrap.servers",
                                broker.getBrokersAsString(),
                                "key.serializer",
                                StringSerializer.class,
                                "value.serializer",
                                StringSerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, Long.toString(BROADCAST), value));
            producer.flush();
        }
    }

    private ConsumerRecord<String, String> pollDeadLetter() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps(
                        broker.getBrokersAsString(), "dead-letter-reader", "true");
        try (Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<>(
                                props, new StringDeserializer(), new StringDeserializer())
                        .createConsumer()) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            return KafkaTestUtils.getSingleRecord(
                    consumer, DEAD_LETTER_TOPIC, Duration.ofSeconds(30));
        }
    }
}
