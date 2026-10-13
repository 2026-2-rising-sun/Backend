package com.shoppinglive.notification.inbox.api;

import com.shoppinglive.notification.inbox.application.NotificationPage;
import java.util.List;

/** 알림 목록 한 구간. 다음 구간은 {@code nextCursor} 를 그대로 다시 넘겨서 받는다. */
public record NotificationListResponse(List<NotificationItemResponse> items, String nextCursor, boolean hasNext) {

    static NotificationListResponse of(NotificationPage page) {
        List<NotificationItemResponse> items = page.items().stream().map(NotificationItemResponse::of).toList();
        String nextCursor = page.hasNext() && !page.items().isEmpty()
                ? NotificationCursor.encode(page.items().get(page.items().size() - 1))
                : null;
        return new NotificationListResponse(items, nextCursor, page.hasNext());
    }
}
