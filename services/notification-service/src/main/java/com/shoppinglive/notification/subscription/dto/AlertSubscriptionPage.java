package com.shoppinglive.notification.subscription.dto;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.List;

public record AlertSubscriptionPage(
        List<AlertSubscription> items, String nextCursor, boolean hasNext) {
    public static AlertSubscriptionPage of(List<AlertSubscription> rows, int size) {
        boolean hasNext = rows.size() > size;
        List<AlertSubscription> items = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
        return new AlertSubscriptionPage(
                items, hasNext ? Long.toString(items.getLast().targetId()) : null, hasNext);
    }

    public static int pageSize(Integer size) {
        if (size == null) return 20;
        if (size < 1 || size > 50)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~50 사이여야 합니다.");
        return size;
    }

    public static Long cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            long id = Long.parseLong(cursor);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "유효하지 않은 알림 신청 커서입니다.");
    }
}
