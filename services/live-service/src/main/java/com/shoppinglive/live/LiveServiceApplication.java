package com.shoppinglive.live;

import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.kafka.KafkaCommonConfig;
import com.shoppinglive.common.local.LocalApplication;
import com.shoppinglive.common.outbox.OutboxConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({JpaAuditingConfig.class, KafkaCommonConfig.class, OutboxConfiguration.class})
public class LiveServiceApplication {

    public static void main(String[] args) {
        LocalApplication.run(LiveServiceApplication.class, args);
    }
}
