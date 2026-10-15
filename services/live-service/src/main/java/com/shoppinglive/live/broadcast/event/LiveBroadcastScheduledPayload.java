package com.shoppinglive.live.broadcast.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 방송 등록과, 준비 중 제목·예정 시각 수정이 커밋될 때마다 발행한다. 그 시점의 일정 전체를 싣는다. 알림 서비스는 broadcastId 로 upsert 해서 "방송 시작
 * 임박" 알림 시각을 scheduledAt 으로 계산한다. 같은 등록 키 재요청(멱등 재응답)에서는 발행하지 않는다.
 *
 * @param broadcastVersion 이벤트 시점의 방송 version. 소비자는 저장된 값 이하이면 무시한다
 */
public record LiveBroadcastScheduledPayload(
        long broadcastId, long broadcastVersion, String title, Instant scheduledAt) {

    public LiveBroadcastScheduledPayload {
        if (broadcastId <= 0) throw new IllegalArgumentException("broadcastId는 양수여야 합니다.");
        if (broadcastVersion < 0)
            throw new IllegalArgumentException("broadcastVersion은 0 이상이어야 합니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
        Objects.requireNonNull(scheduledAt, "scheduledAt은 필수입니다.");
    }
}
