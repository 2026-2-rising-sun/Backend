package com.shoppinglive.notification.inbox.api;

import com.shoppinglive.notification.inbox.domain.Notification;
import java.time.Instant;

/**
 * 알림함에 보여줄 알림 1건.
 *
 * <p>{@code readAt} 이 비어 있으면 안 읽은 알림이다. 안 읽음 여부를 따로 내려주지 않는 이유는
 * 두 값이 어긋날 수 있기 때문이다 — 하나만 두고 화면이 판단한다.
 *
 * <p>알림 종류와 "눌렀을 때 보낼 곳" 은 이벤트 계약이 확정된 뒤에 필드를 추가한다. 응답에 필드가
 * 늘어나는 것은 기존 클라이언트를 깨지 않는다.
 */
public record NotificationItemResponse(Long id, String title, String body, Instant occurredAt, Instant readAt) {

    static NotificationItemResponse of(Notification notification) {
        return new NotificationItemResponse(notification.getId(), notification.getTitle(),
                notification.getBody(), notification.getOccurredAt(), notification.getReadAt());
    }
}
