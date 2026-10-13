package com.shoppinglive.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.contracts.events.EventEnvelope;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 전이와 같은 트랜잭션에서 이벤트를 기록한다. 트랜잭션이 롤백되면 이벤트도 남지 않고,
 * 커밋되면 {@link OutboxRelay} 가 언젠가 반드시 Kafka 로 보낸다 (at-least-once).
 * 요청 처리 중에는 Kafka 를 호출하지 않으므로 Kafka 장애가 API 성공을 막지 않는다.
 *
 * <p>append 는 커밋 직후 fast-path 발행을 유도하기 위해 {@link OutboxCommittedEvent} 를 함께
 * 발행한다. 호출자 트랜잭션이 커밋되면 relay 가 즉시 drain 하고, 커밋이 롤백되면 이벤트도 함께
 * 폐기되므로 발행이 일어나지 않는다.
 */
public class EventOutbox {
    private final OutboxMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEvents;

    public EventOutbox(final OutboxMessageRepository repository, final ObjectMapper objectMapper,
                       final ApplicationEventPublisher applicationEvents) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.applicationEvents = applicationEvents;
    }

    /** 반드시 호출자의 트랜잭션 안에서 불러야 한다. 트랜잭션이 없으면 예외다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(final String topic, final EventEnvelope<?> event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("이벤트를 직렬화할 수 없습니다: " + event.eventType(), e);
        }
        repository.save(new OutboxMessage(event.eventId(), event.eventType(), topic,
            event.aggregateId(), payload, Instant.now()));
        applicationEvents.publishEvent(OutboxCommittedEvent.INSTANCE);
    }
}
