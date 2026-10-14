package com.shoppinglive.live.broadcast.event;

/**
 * Live 방송 라이프사이클 이벤트의 전송 규칙.
 *
 * <p>방송 단위 이벤트는 모두 한 토픽에 eventType 으로 구분해 싣고 key 는 broadcastId 다. 그래서 시작 → (쿠폰 드롭 등) → 종료 순서가 한 파티션
 * 안에서 보장되고, 이벤트 종류가 늘어도 소비자는 구독을 바꿀 필요가 없다. 소비자는 모르는 eventType 을 무시해야 한다. 중복(at-least-once)은
 * eventId 로, 순서 역전은 payload 의 broadcastVersion 으로 거른다.
 */
public final class LiveBroadcastEventContract {

    public static final String TOPIC_NAME = "live.broadcast.v1";
    public static final String MESSAGE_KEY_FIELD = "broadcastId";
    public static final String SOURCE = "live-service";
    public static final int SCHEMA_VERSION = 1;

    private LiveBroadcastEventContract() {}

    public static String messageKey(long broadcastId) {
        if (broadcastId <= 0) {
            throw new IllegalArgumentException("broadcastId must be positive");
        }
        return Long.toString(broadcastId);
    }
}
