package com.shoppinglive.notification.inbox.repository;

import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import java.time.Instant;

/**
 * 만들려는 알림 1건의 내용. 받는 사람은 따로 넘긴다 — 한 이벤트로 여러 명에게 같은 내용을 만들기 때문이다.
 *
 * @param eventId 이 알림을 만들게 한 이벤트 ID. 받는 사람과 함께 중복 생성을 막는 기준이다
 * @param occurredAt 알림이 알리는 일이 실제로 일어난 시각. 이벤트를 받은 시각이 아니다
 */
public record NewNotification(
        String eventId,
        String eventType,
        String title,
        String body,
        NotificationLinkType linkType,
        String linkValue,
        Instant occurredAt) {}
