package com.shoppinglive.notification.inbox.dto;

/**
 * 안 읽은 알림 개수. 화면의 배지 숫자다.
 *
 * <p>개수를 서버에서 끊지 않고 실제 값을 준다. "99+" 같은 표시는 플랫폼 규칙이 아니라 화면의 선택이다.
 */
public record UnreadCountResponse(long unreadCount) {}
