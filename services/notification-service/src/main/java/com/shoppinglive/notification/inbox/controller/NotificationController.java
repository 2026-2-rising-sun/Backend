package com.shoppinglive.notification.inbox.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.notification.inbox.dto.NotificationCursor;
import com.shoppinglive.notification.inbox.dto.NotificationListPosition;
import com.shoppinglive.notification.inbox.dto.NotificationListResponse;
import com.shoppinglive.notification.inbox.dto.NotificationPage;
import com.shoppinglive.notification.inbox.dto.UnreadCountResponse;
import com.shoppinglive.notification.inbox.service.NotificationInboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 내 알림함. 로그인한 본인의 알림만 다룬다 — 회원 ID 는 요청 값이 아니라 토큰에서 꺼내므로 남의 알림함을 조회할 방법이 없다. */
@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;

    private final NotificationInboxService inbox;

    public NotificationController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping
    public ApiResponse<NotificationListResponse> list(
            @AuthenticationPrincipal AuthenticatedUser member,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        int pageSize = pageSize(size);
        NotificationListPosition position =
                StringUtils.hasText(cursor) ? NotificationCursor.decode(cursor) : null;
        NotificationPage page = inbox.list(member.memberId(), position, pageSize);
        return ApiResponse.ok(NotificationListResponse.of(page));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(
            @AuthenticationPrincipal AuthenticatedUser member) {
        return ApiResponse.ok(new UnreadCountResponse(inbox.unreadCount(member.memberId())));
    }

    /**
     * 알림 1건 읽음 처리. 여러 번 호출해도 204 다.
     *
     * <p>읽음은 "상태를 바꾸는 행위"라서 전용 경로로 둔다. 알림 본문을 수정하는 PATCH 로 열어두면 클라이언트가 읽은 시각을 임의로 넣거나 되돌릴 수 있고, 그건
     * 실제로 일어난 일과 다르다.
     */
    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Void> read(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable Long notificationId) {
        inbox.markRead(notificationId, member.memberId());
        return ResponseEntity.noContent().build();
    }

    private int pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SIZE;
        }
        if (requested < 1 || requested > MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "size 는 1~" + MAX_SIZE + " 사이여야 합니다.");
        }
        return requested;
    }
}
