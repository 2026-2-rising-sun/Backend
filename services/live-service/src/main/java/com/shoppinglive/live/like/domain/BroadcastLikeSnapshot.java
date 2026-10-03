package com.shoppinglive.live.like.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 방송별 좋아요 합계의 보관본. 실시간 합계는 Redis 에 있고 이 값은 최대 약 10초 뒤따라온다. */
@Entity
@Table(name = "broadcast_like_snapshot")
public class BroadcastLikeSnapshot {
    @Id
    @Column(name = "broadcast_id")
    private Long broadcastId;

    @Column(nullable = false)
    private long total;

    @Column(nullable = false)
    private Instant updatedAt;

    protected BroadcastLikeSnapshot() {
    }

    public long getTotal() {
        return total;
    }
}
