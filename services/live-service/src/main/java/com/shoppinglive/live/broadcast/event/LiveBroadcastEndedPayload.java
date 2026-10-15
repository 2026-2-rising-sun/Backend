package com.shoppinglive.live.broadcast.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * LIVE → ENDED 전이가 커밋될 때 한 번 발행한다. 반복 종료에서는 발행하지 않는다. 쿠폰 드롭이 생기면 닫힌 드롭 목록 같은 필드는 같은 schemaVersion 에
 * 추가한다.
 *
 * @param closedPromotionIds 종료와 함께 닫힌(BROADCAST_ENDED) 프로모션 중 열려 있었던 것들의 id. null 로 와도 빈 리스트로
 *     정규화한다. 열리지 않은 채 끝난 READY 는 포함하지 않는다
 */
public record LiveBroadcastEndedPayload(
        long broadcastId,
        long broadcastVersion,
        Instant startedAt,
        Instant endedAt,
        List<Long> closedPromotionIds) {

    public LiveBroadcastEndedPayload {
        if (broadcastId <= 0) throw new IllegalArgumentException("broadcastId는 양수여야 합니다.");
        if (broadcastVersion < 0)
            throw new IllegalArgumentException("broadcastVersion은 0 이상이어야 합니다.");
        Objects.requireNonNull(startedAt, "startedAt은 필수입니다.");
        Objects.requireNonNull(endedAt, "endedAt은 필수입니다.");
        closedPromotionIds =
                closedPromotionIds == null ? List.of() : List.copyOf(closedPromotionIds);
    }

    public LiveBroadcastEndedPayload(
            long broadcastId, long broadcastVersion, Instant startedAt, Instant endedAt) {
        this(broadcastId, broadcastVersion, startedAt, endedAt, List.of());
    }
}
