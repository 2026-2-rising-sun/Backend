package com.shoppinglive.payment;

import com.shoppinglive.common.outbox.OutboxConfiguration;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({
    JpaAuditingConfig.class,
    OutboxConfiguration.class,
    com.shoppinglive.common.web.WebConfiguration.class
})
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
