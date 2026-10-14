package com.shoppinglive.live.broadcast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** V5의 기존 익명 클릭 스냅샷. 보존하지만 회원별 좋아요 모델에서는 사용하지 않는다. */
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

    protected BroadcastLikeSnapshot() {}

    public long getTotal() {
        return total;
    }
}
