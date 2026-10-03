package com.shoppinglive.live.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 방송 채팅 한 건. 수정하지 않으므로 updatedAt 을 두지 않는다.
 * memberId 는 Member 서비스의 식별자이며 FK 를 걸지 않고 공개 응답에도 넣지 않는다.
 * displayName 은 작성 당시 값이다.
 */
@Entity
@Table(name = "broadcast_chat")
public class BroadcastChat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broadcast_id", nullable = false, updatable = false)
    private Long broadcastId;

    @Column(nullable = false, updatable = false)
    private UUID memberId;

    @Column(nullable = false, updatable = false, length = 80)
    private String displayName;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected BroadcastChat() {
    }

    public BroadcastChat(final Long broadcastId, final UUID memberId, final String displayName,
                         final String content, final Instant createdAt) {
        this.broadcastId = broadcastId;
        this.memberId = memberId;
        this.displayName = displayName;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getBroadcastId() {
        return broadcastId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
