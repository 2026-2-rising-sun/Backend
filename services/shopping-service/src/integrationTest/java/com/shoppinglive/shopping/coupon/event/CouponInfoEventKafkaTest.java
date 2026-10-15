package com.shoppinglive.shopping.coupon.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * 쿠폰 listener 가 DB 연결을 얻지 못할 때의 처리를 실제 Kafka 와 실제 transaction 으로 확인한다.
 *
 * <p>커넥션 풀을 2개로 줄이고 테스트가 둘 다 쥐고 있으면, 쿠폰 저장의 {@code @Transactional} 이 transaction 을 시작하지 못한다. 예외를 직접
 * 만들어 넣지 않으므로, 운영에서 커넥션 풀 고갈이나 DB 접속 불가 때 오는 것과 같은 예외 체인이 error handler 에 전달된다.
 */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            "shopping.events.consumer.retry-interval=10ms",
            // 앱 기동(Flyway)에 연결 2개가 필요해 1개로는 줄일 수 없다.
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.connection-timeout=250",
            "spring.datasource.url=jdbc:h2:mem:shopping_coupon_kafka;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class CouponInfoEventKafkaTest extends ShoppingSecuritySupport {

    private static final String COUPON_ID = "coupon-db-outage";
    // 제한 재시도(처음 1번 + 재시도 5번)라면 이 설정에서 약 2초 안에 DLT 로 넘어간다
    // (시도마다 연결 대기 250ms + 10ms 부터 1.5배씩 늘어나는 간격). 장애를 그보다 충분히 길게 유지한다.
    private static final Duration OUTAGE = Duration.ofSeconds(5);

    @Autowired EmbeddedKafkaBroker broker;
    @Autowired DataSource dataSource;
    @Autowired CouponDisplayRepository repository;

    @Test
    @DisplayName("transaction 시작 때 DB 연결을 얻지 못하면 DLT 로 보내지 않고 계속 재시도하다가, 연결이 돌아오면 반영한다")
    void connectionAcquisitionFailureIsRetriedUntilRecovery() throws Exception {
        try (Connection held1 = dataSource.getConnection();
                Connection held2 = dataSource.getConnection();
                KafkaProducer<String, String> producer =
                        new KafkaProducer<>(
                                Map.of(
                                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                        broker.getBrokersAsString(),
                                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                        StringSerializer.class,
                                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                        StringSerializer.class))) {
            producer.send(
                            new ProducerRecord<>(
                                    CouponInfoEventContract.TOPIC_NAME, COUPON_ID, event()))
                    .get(10, TimeUnit.SECONDS);

            // 시도 횟수는 셀 수 없다. transaction 이 저장소 메서드에 들어가기 전에 실패하기 때문이다.
            // 대신 장애를 제한 재시도가 끝날 시간보다 길게 유지한다. DLT 로 넘어갔다면 아래에서 쿠폰이 끝내 보이지 않는다.
            Thread.sleep(OUTAGE.toMillis());
        } // 여기서 연결 2개를 돌려준다 = DB 장애 복구

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(repository.findOpenCouponIds(Instant.now()))
                                        .contains(COUPON_ID));
    }

    private static String event() {
        return """
                {"eventId":"0f1f0d9e-7c55-4c5a-9a4f-0c9d2b6f2a11","eventType":"CouponCreated",
                 "schemaVersion":1,"source":"commerce-service","aggregateId":"%s",
                 "occurredAt":"2026-10-15T00:00:00Z",
                 "payload":{"couponId":"%s","snapshotVersion":1,"name":"연결 장애 확인 쿠폰",
                   "fixedDiscount":1000,"productIds":[701],
                   "startsAt":"2026-01-01T00:00:00Z","endsAt":"2099-01-01T00:00:00Z",
                   "expiresAt":"2099-02-01T00:00:00Z","soldOut":false}}
                """
                .formatted(COUPON_ID, COUPON_ID);
    }
}
