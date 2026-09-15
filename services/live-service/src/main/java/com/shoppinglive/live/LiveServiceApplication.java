package com.shoppinglive.live;

import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.kafka.KafkaCommonConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({JpaAuditingConfig.class, KafkaCommonConfig.class})
public class LiveServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveServiceApplication.class, args);
    }
}
