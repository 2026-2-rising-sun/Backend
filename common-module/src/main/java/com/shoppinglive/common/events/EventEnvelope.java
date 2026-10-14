package com.shoppinglive.common.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 서비스별 payload와 독립적인 이벤트 메타데이터. 전송·중복 제거·순서 처리는 별도 구현이 필요하다. */
public record EventEnvelope<P>(
        String eventId,
        String eventType,
        int schemaVersion,
        String source,
        String aggregateId,
        Instant occurredAt,
        P payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId는 필수입니다.");
        try {
            if (!UUID.fromString(eventId).toString().equalsIgnoreCase(eventId)) {
                throw new IllegalArgumentException("eventId는 표준 UUID 문자열 형식이어야 합니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("eventId는 표준 UUID 문자열 형식이어야 합니다.", exception);
        }
        requireText(eventType, "eventType");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion은 양수여야 합니다.");
        requireText(source, "source");
        requireText(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다.");
        Objects.requireNonNull(payload, "payload는 필수입니다.");
    }

    public static <P> EventEnvelope<P> create(
            String eventType,
            int schemaVersion,
            String source,
            String aggregateId,
            Instant occurredAt,
            P payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                schemaVersion,
                source,
                aggregateId,
                occurredAt,
                payload);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + "는 필수입니다.");
    }
}
