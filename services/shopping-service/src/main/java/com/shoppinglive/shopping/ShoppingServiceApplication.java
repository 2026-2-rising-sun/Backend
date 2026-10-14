package com.shoppinglive.shopping;

import com.shoppinglive.common.local.LocalApplication;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.security.AuthenticationConfiguration;
import com.shoppinglive.common.web.WebConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import({JpaAuditingConfig.class, WebConfiguration.class, AuthenticationConfiguration.class})
public class ShoppingServiceApplication {

    public static void main(String[] args) {
        LocalApplication.run(ShoppingServiceApplication.class, args);
    }
}
