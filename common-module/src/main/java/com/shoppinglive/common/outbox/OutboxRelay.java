package com.shoppinglive.common.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 미발행 outbox 행을 기록 순서대로 Kafka 에 보낸다. 한 건이라도 실패하면 그 주기를 멈춰서 뒤 이벤트가 앞 이벤트를 앞지르지 않게 하고, 다음 주기에 실패한 행부터
 * 다시 보낸다.
 *
 * <p>발행 경로는 두 개다.
 *
 * <ol>
 *   <li>Fast-path: {@link OutboxCommittedEvent} 를 {@code AFTER_COMMIT} 으로 받아 즉시 drain. 지연이 ~1s 폴링이
 *       아니라 커밋 직후 몇 ms 수준으로 떨어진다.
 *   <li>Safety net: 매 {@code shoppinglive.outbox.relay.fixed-delay}(기본 1s) 폴링. 커밋 직후 호출이 Kafka
 *       미도달·일시 장애로 실패한 행을 다시 집어 보낸다.
 * </ol>
 *
 * 두 경로가 동시에 돌아 같은 행을 중복 전송하지 않도록 {@link AtomicBoolean} 으로 단일 흐름을 보장한다. 전송 성공 후 발행 표시 전에 죽거나 인스턴스 둘이
 * 같은 행을 보내면 중복이 생길 수 있고, 소비자는 eventId 로 중복을 거른다. 전송은 트랜잭션 밖에서 하므로 DB 잠금을 오래 잡지 않는다.
 */
public class OutboxRelay {
    public static final String EVENT_TYPE_HEADER = "eventType";
    public static final String EVENT_ID_HEADER = "eventId";

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMessageRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final java.util.concurrent.Executor fastPath;
    private final AtomicBoolean draining = new AtomicBoolean();

    public OutboxRelay(
            final OutboxMessageRepository repository,
            final KafkaTemplate<String, String> kafka,
            final OutboxProperties properties,
            java.util.concurrent.Executor fastPath) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.fastPath = fastPath;
    }

    @Scheduled(fixedDelayString = "${shoppinglive.outbox.relay.fixed-delay:1s}")
    public void relayScheduled() {
        relay();
    }

    /** append 트랜잭션이 커밋되면 즉시 drain 을 시도한다. 이미 돌고 있으면 skip. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOutboxCommitted(final OutboxCommittedEvent event) {
        fastPath.execute(
                () -> {
                    try {
                        relay();
                    } catch (RuntimeException failure) {
                        log.warn("Outbox 빠른 발행 실패. 정기 작업에서 재시도합니다.", failure);
                    }
                });
    }

    /** 이번 주기에 발행한 건수를 돌려준다. 재진입은 막혀, 폴링과 fast-path 가 겹쳐도 1회만 돈다. */
    public int relay() {
        if (!draining.compareAndSet(false, true)) {
            return 0;
        }
        try {
            return drainBatch();
        } finally {
            draining.set(false);
        }
    }

    private int drainBatch() {
        final List<OutboxMessage> pending =
                repository.findByPublishedAtIsNullOrderByIdAsc(
                        PageRequest.ofSize(properties.batchSize()));
        int published = 0;
        for (final OutboxMessage message : pending) {
            try {
                send(message);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn(
                        "outbox 발행 실패 eventId={} topic={} attempts={}",
                        message.getEventId(),
                        message.getTopic(),
                        message.getAttempts() + 1,
                        e);
                repository.markFailed(message.getId(), truncate(e.toString()));
                break;
            }
            repository.markPublished(message.getId(), Instant.now());
            published++;
        }
        return published;
    }

    private void send(final OutboxMessage message) throws Exception {
        final ProducerRecord<String, String> record =
                new ProducerRecord<>(
                        message.getTopic(), message.getMessageKey(), message.getPayload());
        record.headers()
                .add(EVENT_TYPE_HEADER, message.getEventType().getBytes(StandardCharsets.UTF_8))
                .add(EVENT_ID_HEADER, message.getEventId().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String truncate(final String error) {
        return error.length() <= OutboxMessage.MAX_ERROR_LENGTH
                ? error
                : error.substring(0, OutboxMessage.MAX_ERROR_LENGTH);
    }
}
