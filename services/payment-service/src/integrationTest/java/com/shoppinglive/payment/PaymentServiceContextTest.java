package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "spring.datasource.url=jdbc:h2:mem:payment_context;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.kafka.bootstrap-servers=localhost:1",
            "shoppinglive.outbox.relay.enabled=false",
            "payment.core-service-token=payment-context-test-service-credential-32"
        })
class PaymentServiceContextTest {
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("기존 Mock 결제 API 없이 컨트롤러 0개로 기동한다")
    void startsWithoutControllers() {
        assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
    }
}
