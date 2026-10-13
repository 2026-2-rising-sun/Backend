package com.shoppinglive.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 모든 도메인 이벤트가 공유하는 공통 봉투. 전송·중복 제거·순서 판단에 필요한 필드만 봉투에 두고,
 * 도메인 데이터는 {@code payload} 에 둔다.
 *
 * <p>새 이벤트를 추가할 때 봉투·outbox·릴레이는 바꾸지 않는다. payload record 와 eventType 이름만
 * 추가한다. eventType 은 enum 이 아니라 문자열이라 소비자는 모르는 타입을 받아도 역직렬화에
 * 실패하지 않고 건너뛸 수 있다. payload 필드 추가는 같은 schemaVersion, 삭제·의미 변경은 새 버전이다.
 *
 * @param eventType   이벤트 이름. 예: {@code LiveBroadcastStarted}
 * @param source      발행 서비스. 예: {@code live-service}
 * @param aggregateId 이벤트가 속한 대상의 id 이자 Kafka key. 같은 대상의 이벤트는 같은 파티션으로 간다
 */
public record EventEnvelope<P>(
        String eventId,
        String eventType,
        int schemaVersion,
        String source,
        String aggregateId,
        Instant occurredAt,
        P payload)
        implements DomainEvent {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId must not be null");
        try {
            if (!UUID.fromString(eventId).toString().equalsIgnoreCase(eventId)) {
                throw new IllegalArgumentException("eventId must be a canonical UUID string");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("eventId must be a canonical UUID string", exception);
        }
        requireText(eventType, "eventType");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requireText(source, "source");
        requireText(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /** 새 eventId 를 발급해 봉투를 만든다. */
    public static <P> EventEnvelope<P> create(String eventType, int schemaVersion, String source,
                                              String aggregateId, Instant occurredAt, P payload) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), eventType, schemaVersion, source,
                aggregateId, occurredAt, payload);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
