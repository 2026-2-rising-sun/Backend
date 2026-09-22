package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.integration.ivs.IvsReadiness;
import java.time.Instant;

public record PublicBroadcastResponse(
        Long id,
        String title,
        Instant scheduledAt,
        String status,
        String playbackUrl,
        boolean playbackAllowed,
        IvsReadiness videoStatus,
        Instant createdAt
) {
    public static PublicBroadcastResponse from(final Broadcast broadcast) {
        return from(broadcast, null);
    }

    /**
     * videoStatus 는 지금 이 순간의 영상 신호이고 playbackAllowed 는 업무 상태다.
     * 둘은 분리되어 있어 OBS 가 잠시 끊겨도 업무 상태 LIVE 는 유지된다.
     * 목록 응답은 N+1 을 만들지 않도록 videoStatus 를 채우지 않는다(null).
     */
    public static PublicBroadcastResponse from(final Broadcast broadcast,
                                               final IvsReadiness videoStatus) {
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
                playbackAllowed ? videoStatus : null,
                broadcast.getCreatedAt()
        );
    }
}
