package com.shoppinglive.live.broadcast.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 방송 중 이벤트(프로모션)가 CLOSED 로 전이할 때 한 번 발행한다. 반복 닫기(멱등 응답)에서는 발행하지 않고, 열려 있던 적이 있는(wasOpen) 프로모션에 대해서만
 * 발행한다.
 *
 * <p>{@code reason} 값은 {@code SELLER_CLOSED} · {@code EXPIRED} · {@code BROADCAST_ENDED} · {@code
 * CANCELED} 중 하나다. 소비자는 모르는 reason 을 받아도 역직렬화에 실패하지 않는다.
 *
 * <p>소비자 dedup 규칙은 {@link LiveBroadcastPromotionOpenedPayload} 와 같다 — 봉투의 {@code eventId} 로 거른다.
 *
 * @param openedAt 열렸던 시각. READY 에서 바로 닫힌 경우(열림 발행 없음)엔 null 이지만, 그 경우에는 이 이벤트 자체가 발행되지 않는다. 안전을 위해
 *     null 허용으로 둔다
 */
public record LiveBroadcastPromotionClosedPayload(
        long broadcastId,
        long promotionId,
        long promotionVersion,
        String type,
        String reason,
        Instant openedAt,
        Instant closedAt) {

    public LiveBroadcastPromotionClosedPayload {
        if (broadcastId <= 0) throw new IllegalArgumentException("broadcastId는 양수여야 합니다.");
        if (promotionId <= 0) throw new IllegalArgumentException("promotionId는 양수여야 합니다.");
        if (promotionVersion < 0)
            throw new IllegalArgumentException("promotionVersion은 0 이상이어야 합니다.");
        requireText(type, "type");
        requireText(reason, "reason");
        Objects.requireNonNull(closedAt, "closedAt은 필수입니다.");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "은 비어 있을 수 없습니다.");
        }
    }
}
