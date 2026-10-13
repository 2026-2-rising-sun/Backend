package com.shoppinglive.notification;

import com.shoppinglive.notification.security.NotificationSecuritySupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@DisplayName("notification-service 스프링 컨텍스트 기동 검증")
class NotificationServiceApplicationTests extends NotificationSecuritySupport {

    @DisplayName("스프링 애플리케이션 컨텍스트가 정상적으로 로드된다")
    @Test
    void contextLoads() {
    }
}
