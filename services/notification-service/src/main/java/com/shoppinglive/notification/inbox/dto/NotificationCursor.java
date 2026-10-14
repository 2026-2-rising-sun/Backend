package com.shoppinglive.notification.inbox.dto;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.notification.inbox.entity.Notification;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 목록의 마지막 위치를 담은 커서. 속을 알 수 없는 문자열로 주고받는다.
 *
 * <p>정렬 기준 값(발생 시각·id)을 그대로 노출하면 그게 API 계약이 되어, 나중에 정렬을 바꿀 때 클라이언트가 깨진다. 인코딩해서 넘기면 서버가 안에 무엇을 담는지
 * 자유롭게 바꿀 수 있다.
 */
public final class NotificationCursor {

    private static final char SEPARATOR = '_';

    private NotificationCursor() {}

    public static String encode(Notification last) {
        String raw = last.getOccurredAt().toString() + SEPARATOR + last.getId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @throws BusinessException INVALID_REQUEST — 위조·손상된 커서
     */
    public static NotificationListPosition decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf(SEPARATOR);
            Instant occurredAt = Instant.parse(raw.substring(0, separator));
            long id = Long.parseLong(raw.substring(separator + 1));
            return new NotificationListPosition(occurredAt, id);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.");
        }
    }
}
