package com.shoppinglive.live.broadcast.dto;

import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.entity.BroadcastStatus;
import java.time.Instant;

/**
 * 관리 응답. 운영자가 채널/재생 URL 일치를 확인해야 하므로 IVS 필드를 포함한다. 공개 응답은 PublicBroadcastResponse 에서 별도로 최소 필드만
 * 노출한다.
 */
public record BroadcastResponse(
        Long id,
        String title,
        Instant scheduledAt,
        String channelArn,
        String playbackUrl,
        BroadcastStatus status,
        Instant startedAt,
        Instant endedAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String hostName,
        String description,
        String thumbnailId,
        String thumbnailUrl) {
    public static BroadcastResponse from(final Broadcast broadcast) {
        return new BroadcastResponse(
                broadcast.getId(),
                broadcast.getTitle(),
                broadcast.getScheduledAt(),
                broadcast.getChannelArn(),
                broadcast.getPlaybackUrl(),
                broadcast.getStatus(),
                broadcast.getStartedAt(),
                broadcast.getEndedAt(),
                broadcast.getVersion(),
                broadcast.getCreatedAt(),
                broadcast.getUpdatedAt(),
                broadcast.getHostName(),
                broadcast.getDescription(),
                broadcast.getThumbnailId(),
                broadcast.getThumbnailUrl());
    }
}
