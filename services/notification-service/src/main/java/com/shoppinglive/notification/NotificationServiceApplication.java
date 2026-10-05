package com.shoppinglive.notification;

import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.kafka.KafkaCommonConfig;
import com.shoppinglive.common.local.LocalApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({JpaAuditingConfig.class, KafkaCommonConfig.class})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        LocalApplication.run(NotificationServiceApplication.class, args);
    }
}
