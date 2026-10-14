package com.shoppinglive.live.broadcast.event;

import java.time.Instant;
import java.util.Objects;

/**
 * LIVE → ENDED 전이가 커밋될 때 한 번 발행한다. 반복 종료에서는 발행하지 않는다. 쿠폰 드롭이 생기면 닫힌 드롭 목록 같은 필드는 같은 schemaVersion 에
 * 추가한다.
 */
public record LiveBroadcastEndedPayload(
        long broadcastId, long broadcastVersion, Instant startedAt, Instant endedAt) {

    public LiveBroadcastEndedPayload {
        if (broadcastId <= 0) throw new IllegalArgumentException("broadcastId must be positive");
        if (broadcastVersion < 0)
            throw new IllegalArgumentException("broadcastVersion must not be negative");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null");
    }
}
