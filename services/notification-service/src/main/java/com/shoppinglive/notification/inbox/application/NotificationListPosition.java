package com.shoppinglive.notification.inbox.application;

import java.time.Instant;

/**
 * 알림 목록에서 "여기 다음부터" 를 가리키는 위치. 커서가 담고 있는 값이다.
 *
 * <p>시각만으로는 부족하다 — 같은 시각의 알림이 여러 건일 수 있어서 id 까지 있어야 지점이 하나로 정해진다.
 */
public record NotificationListPosition(Instant occurredAt, Long id) {
}
