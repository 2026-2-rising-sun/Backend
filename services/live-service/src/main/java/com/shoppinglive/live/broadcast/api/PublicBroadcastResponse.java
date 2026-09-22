package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import java.time.Instant;

public record PublicBroadcastResponse(
        Long id,
        String title,
        Instant scheduledAt,
        String status,
        String playbackUrl,
        boolean playbackAllowed,
        Instant createdAt
) {
    public static PublicBroadcastResponse from(final Broadcast broadcast) {
        final String status = switch (broadcast.getStatus()) {
            case PREPARING -> "예정";
            case LIVE -> "진행";
            case ENDED -> "종료";
        };

        final String playbackUrl = broadcast.getStatus() == BroadcastStatus.LIVE
                ? broadcast.getPlaybackUrl()
                : null;

        final boolean playbackAllowed = broadcast.getStatus() == BroadcastStatus.LIVE;

        return new PublicBroadcastResponse(
                broadcast.getId(),
                broadcast.getTitle(),
                broadcast.getScheduledAt(),
                status,
                playbackUrl,
                playbackAllowed,
                broadcast.getCreatedAt()
        );
    }
}
