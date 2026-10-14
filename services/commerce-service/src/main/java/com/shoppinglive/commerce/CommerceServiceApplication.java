package com.shoppinglive.commerce;

import com.shoppinglive.common.local.LocalApplication;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.security.AuthenticationConfiguration;
import com.shoppinglive.common.web.WebConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({
    JpaAuditingConfig.class,
    WebConfiguration.class,
    AuthenticationConfiguration.class,
    com.shoppinglive.common.outbox.OutboxConfiguration.class
})
public class CommerceServiceApplication {

    public static void main(String[] args) {
        LocalApplication.run(CommerceServiceApplication.class, args);
    }
}
