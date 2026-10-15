package com.shoppinglive.notification;

import com.shoppinglive.common.local.LocalApplication;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.security.AuthenticationConfiguration;
import com.shoppinglive.common.web.WebConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 방송 시작 임박 알림을 주기적으로 만들기 때문에 스케줄링을 켠다. */
@SpringBootApplication
@EnableScheduling
@Import({JpaAuditingConfig.class, WebConfiguration.class, AuthenticationConfiguration.class})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        LocalApplication.run(NotificationServiceApplication.class, args);
    }
}
