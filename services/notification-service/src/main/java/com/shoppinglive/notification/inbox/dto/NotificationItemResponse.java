package com.shoppinglive.notification.inbox.dto;

import com.shoppinglive.notification.inbox.entity.Notification;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import java.time.Instant;

/**
 * 알림함에 보여줄 알림 1건.
 *
 * <p>{@code readAt} 이 비어 있으면 안 읽은 알림이다. 안 읽음 여부를 따로 내려주지 않는 이유는 두 값이 어긋날 수 있기 때문이다 — 하나만 두고 화면이
 * 판단한다.
 *
 * <p>{@code eventType} 은 화면이 아이콘을 고르는 근거이고, {@code linkType}·{@code linkValue} 는 눌렀을 때 보낼 곳이다. 보낼
 * 곳이 없는 알림은 둘 다 비어 있다.
 */
public record NotificationItemResponse(
        Long id,
        String eventType,
        String title,
        String body,
        NotificationLinkType linkType,
        String linkValue,
        Instant occurredAt,
        Instant readAt) {

    static NotificationItemResponse of(Notification notification) {
        return new NotificationItemResponse(
                notification.getId(),
                notification.getEventType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getLinkType(),
                notification.getLinkValue(),
                notification.getOccurredAt(),
                notification.getReadAt());
    }
}
