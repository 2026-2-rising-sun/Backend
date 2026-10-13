package com.shoppinglive.common.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 미발행 outbox 행을 기록 순서대로 Kafka 에 보낸다. 한 건이라도 실패하면 그 주기를 멈춰서
 * 뒤 이벤트가 앞 이벤트를 앞지르지 않게 하고, 다음 주기에 실패한 행부터 다시 보낸다.
 *
 * <p>전송 성공 후 발행 표시 전에 죽거나 인스턴스 둘이 같은 행을 보내면 중복이 생길 수 있다.
 * 소비자는 eventId 로 중복을 거른다. 전송은 트랜잭션 밖에서 하므로 DB 잠금을 오래 잡지 않는다.
 */
public class OutboxRelay {
    public static final String EVENT_TYPE_HEADER = "eventType";
    public static final String EVENT_ID_HEADER = "eventId";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMessageRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    public OutboxRelay(final OutboxMessageRepository repository,
                       final KafkaTemplate<String, String> kafka,
                       final OutboxProperties properties) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${shoppinglive.outbox.relay.fixed-delay:1s}")
    public void relayScheduled() {
        relay();
    }

    /** 이번 주기에 발행한 건수를 돌려준다. */
    public int relay() {
        final List<OutboxMessage> pending = repository.findByPublishedAtIsNullOrderByIdAsc(
            PageRequest.ofSize(properties.batchSize()));
        int published = 0;
        for (final OutboxMessage message : pending) {
            try {
                send(message);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("outbox 발행 실패 eventId={} topic={} attempts={}", message.getEventId(),
                    message.getTopic(), message.getAttempts() + 1, e);
                repository.markFailed(message.getId(), truncate(e.toString()));
                break;
            }
            repository.markPublished(message.getId(), Instant.now());
            published++;
        }
        return published;
    }

    private void send(final OutboxMessage message) throws Exception {
        final ProducerRecord<String, String> record = new ProducerRecord<>(
            message.getTopic(), message.getMessageKey(), message.getPayload());
        record.headers()
            .add(EVENT_TYPE_HEADER, message.getEventType().getBytes(StandardCharsets.UTF_8))
            .add(EVENT_ID_HEADER, message.getEventId().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String truncate(final String error) {
        return error.length() <= OutboxMessage.MAX_ERROR_LENGTH
            ? error : error.substring(0, OutboxMessage.MAX_ERROR_LENGTH);
    }
}
