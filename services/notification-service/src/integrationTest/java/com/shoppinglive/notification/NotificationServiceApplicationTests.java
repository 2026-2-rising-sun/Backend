package com.shoppinglive.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.notification.inbox.entity.Notification;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NotificationRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("notification-service 스프링 컨텍스트 기동 검증")
class NotificationServiceApplicationTests extends NotificationSecuritySupport {

    @Autowired NotificationRepository notifications;

    @Test
    @DisplayName("알림 저장 시 감사 시간을 기록하고 최초 읽음 시각을 보존한다")
    void notificationPersistenceRetainsFirstReadAndAuditTimes() {
        var notification =
                notifications.saveAndFlush(
                        new Notification(
                                "11111111-1111-4111-8111-111111111111",
                                "22222222-2222-4222-8222-222222222222",
                                "LiveBroadcastStarted",
                                "방송 시작",
                                "방송이 시작되었습니다.",
                                NotificationLinkType.BROADCAST,
                                "42",
                                Instant.parse("2026-10-14T00:00:00Z")));
        var firstRead = Instant.parse("2026-10-14T00:01:00Z");
        notification.markRead(firstRead);
        notification.markRead(firstRead.plusSeconds(1));
        notification = notifications.saveAndFlush(notification);
        var stored = notifications.findById(notification.getId()).orElseThrow();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
        assertThat(stored.getReadAt()).isEqualTo(firstRead);
        notifications.deleteById(stored.getId());
    }

    @DisplayName("스프링 애플리케이션 컨텍스트가 정상적으로 로드된다")
    @Test
    void contextLoads() {}
}
