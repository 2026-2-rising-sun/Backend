package com.shoppinglive.notification.broadcast.event;

/**
 * Live 방송 이벤트 계약 중 알림이 쓰는 부분의 사본.
 *
 * <p>서비스끼리는 코드를 의존하지 않으므로(모듈 경계) 소비자도 계약을 따로 들고 있는다. Shopping 이 판매정보 계약 사본을 들고 있는 것과 같은 방식이다. 발행 측이
 * 바뀌면 이 사본과 {@link LiveBroadcastEventMessage} 의 검증이 깨지면서 드러난다.
 *
 * <p>방송 단위 이벤트는 모두 한 토픽에 들어오고 {@code eventType} 으로 구분한다. 그래서 <b>모르는 eventType 은 무시</b>해야 한다 — 이벤트
 * 종류가 늘어도 알림은 구독을 바꾸지 않는다.
 */
public final class LiveBroadcastEventContract {

    public static final String TOPIC_NAME = "live.broadcast.v1";
    public static final String SOURCE = "live-service";
    public static final int SCHEMA_VERSION = 1;

    /** 방송 등록과 일정 변경. 시작 임박 알림 시각의 근거다. */
    public static final String SCHEDULED = "LiveBroadcastScheduled";

    /** 준비중에서 진행중으로 바뀔 때 한 번. 이미 진행중인 멱등 재시도에서는 발행되지 않는다. */
    public static final String STARTED = "LiveBroadcastStarted";

    private LiveBroadcastEventContract() {}
}
