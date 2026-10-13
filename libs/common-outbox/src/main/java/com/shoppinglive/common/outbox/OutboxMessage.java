package com.shoppinglive.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 발행 대기 중인 이벤트 한 건. payload 는 직렬화된 봉투 전체라 테이블은 이벤트 종류를 모른다.
 * 서비스마다 자기 DB 에 같은 모양의 {@code event_outbox} 테이블을 Flyway 로 만든다.
 */
@Entity
@Table(name = "event_outbox")
public class OutboxMessage {
    static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String eventId;

    @Column(nullable = false, updatable = false, length = 128)
    private String eventType;

    @Column(nullable = false, updatable = false, length = 128)
    private String topic;

    @Column(nullable = false, updatable = false, length = 128)
    private String messageKey;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    protected OutboxMessage() {
    }

    OutboxMessage(final String eventId, final String eventType, final String topic,
                  final String messageKey, final String payload, final Instant createdAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
