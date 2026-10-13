package com.shoppinglive.contracts.events;

/**
 * live.broadcast.v1 토픽에 실리는 이벤트 이름. 방송 단위 이벤트(쿠폰 드롭, 일정 변경 등)는
 * 토픽을 새로 만들지 않고 여기에 이름과 payload 만 추가한다.
 */
public enum LiveBroadcastEventType {
    LiveBroadcastScheduled,
    LiveBroadcastStarted,
    LiveBroadcastEnded,
    LiveBroadcastPromotionOpened,
    LiveBroadcastPromotionClosed
}
