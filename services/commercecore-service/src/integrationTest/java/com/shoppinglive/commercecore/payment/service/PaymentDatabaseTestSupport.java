package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.support.CommerceSecurityTestSupport;
import org.springframework.test.context.*;

public abstract class PaymentDatabaseTestSupport extends CommerceSecurityTestSupport {
    private static final String SCHEMA =
            "core_payment_" + java.util.UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void paymentDatabase(DynamicPropertyRegistry p) {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        if (url == null) return;
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("COMMERCECORE_TEST_POSTGRES_USER"));
        p.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.flyway.enabled", () -> true);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
