package com.shoppinglive.live.broadcast.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * PREPARING → LIVE 전이가 커밋될 때 한 번 발행한다. 이미 LIVE 인 멱등 재시도에서는 발행하지 않는다.
 *
 * @param broadcastVersion 이벤트 시점의 방송 version. 소비자는 저장된 값 이하이면 무시한다
 * @param productIds 시작 시점에 공개 가능(ON_SALE/SOLD_OUT)했던 연결 상품, 노출 순서
 */
public record LiveBroadcastStartedPayload(
        long broadcastId,
        long broadcastVersion,
        String title,
        Instant scheduledAt,
        Instant startedAt,
        List<Long> productIds) {

    public LiveBroadcastStartedPayload {
        if (broadcastId <= 0) throw new IllegalArgumentException("broadcastId는 양수여야 합니다.");
        if (broadcastVersion < 0)
            throw new IllegalArgumentException("broadcastVersion은 0 이상이어야 합니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
        Objects.requireNonNull(scheduledAt, "scheduledAt은 필수입니다.");
        Objects.requireNonNull(startedAt, "startedAt은 필수입니다.");
        productIds = List.copyOf(Objects.requireNonNull(productIds, "productIds는 필수입니다."));
    }
}
