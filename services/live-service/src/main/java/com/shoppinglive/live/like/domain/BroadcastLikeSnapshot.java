package com.shoppinglive.live.like.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** V5 legacy anonymous click snapshot, retained but ignored by the member-like model. */
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
