package com.shoppinglive.live.like.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "broadcast_like_aggregate")
public class LikeAggregate {
    @Id
    @Column(name = "broadcast_id", nullable = false)
    private Long broadcastId;
    @Column(name = "total", nullable = false)
    private long total;
    @Column(name = "version", nullable = false)
    private long version;

    protected LikeAggregate() {}
}
