package com.shoppinglive.notification.inbox.application;

import com.shoppinglive.notification.inbox.domain.Notification;
import java.util.List;

/**
 * 알림 목록 한 구간과 "더 있는지" 여부.
 *
 * <p>전체 건수는 담지 않는다. 무한스크롤 화면은 전체 건수를 쓰지 않고, 매번 세는 비용만 든다.
 */
public record NotificationPage(List<Notification> items, boolean hasNext) {
}
