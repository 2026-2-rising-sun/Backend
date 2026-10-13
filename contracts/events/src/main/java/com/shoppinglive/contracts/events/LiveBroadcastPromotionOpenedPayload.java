package com.shoppinglive.contracts.events;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 방송 중 이벤트(프로모션)가 READY → OPEN 으로 전이할 때 한 번 발행한다. 반복 열기(멱등 응답)에서는
 * 발행하지 않는다. 라이브는 {@code attributes} 를 해석하지 않고 생성 요청의 Map 을 그대로 싣는다.
 *
 * <p>READY 상태로만 끝난 프로모션(열리지 않은 채 방송 종료·취소)은 Kafka 에 흔적이 없다.
 * Opened 가 발행됐으면 반드시 Closed 도 발행된다.
 *
 * <p><b>소비자는 봉투의 {@code eventId} 로 dedup 해야 한다.</b> 라이브는 at-least-once 전송이라
 * fast-path 성공 뒤 mark-published 전에 pod 가 죽거나 다중 pod 폴링이 겹치면 같은 메시지가 둘
 * 이상 올 수 있다. 쿠폰 발급창은 "이미 열려있으면 noop" 으로 자연 멱등이지만, 사용자 알림처럼
 * side effect 가 재적용되면 안 되는 소비자는 Redis set 등으로 eventId 기반 dedup 을 반드시 둔다.
 *
 * @param closesAt 자동 만료 시각. {@code durationSeconds} 미지정이면 null
 */
public record LiveBroadcastPromotionOpenedPayload(
        long broadcastId,
        long promotionId,
        long promotionVersion,
        String type,
        String title,
        Map<String, Object> attributes,
        Instant openedAt,
        Instant closesAt) {

    public LiveBroadcastPromotionOpenedPayload {
        if (broadcastId <= 0) throw new IllegalArgumentException("broadcastId must be positive");
        if (promotionId <= 0) throw new IllegalArgumentException("promotionId must be positive");
        if (promotionVersion < 0) throw new IllegalArgumentException("promotionVersion must not be negative");
        requireText(type, "type");
        requireText(title, "title");
        Objects.requireNonNull(attributes, "attributes must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        // 키 순서를 결정론적으로 고정해 소비자가 재직렬화해도 같은 바이트가 되게 한다.
        attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
