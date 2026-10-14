package com.shoppinglive.notification;

import com.shoppinglive.common.local.LocalApplication;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.security.AuthenticationConfiguration;
import com.shoppinglive.common.web.WebConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({JpaAuditingConfig.class, WebConfiguration.class, AuthenticationConfiguration.class})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        LocalApplication.run(NotificationServiceApplication.class, args);
    }
}
