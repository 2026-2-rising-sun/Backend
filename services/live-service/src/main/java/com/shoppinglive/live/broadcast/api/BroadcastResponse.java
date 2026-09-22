package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import java.time.Instant;

public record BroadcastResponse(
    long id,
    String title,
    Instant scheduledAt,
    BroadcastStatus status,
    long version,
    Instant createdAt
) {
    public static BroadcastResponse from(final Broadcast broadcast) {
        return new BroadcastResponse(
            broadcast.getId(),
            broadcast.getTitle(),
            broadcast.getScheduledAt(),
            broadcast.getStatus(),
            broadcast.getVersion(),
            broadcast.getCreatedAt()
        );
    }
}
