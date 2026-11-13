package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.payment.observability.PaymentObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.profiles.active=test,prod",
            "spring.datasource.url=jdbc:h2:mem:payment_security;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.kafka.bootstrap-servers=localhost:1",
            "shoppinglive.outbox.relay.enabled=false",
            "payment.core-service-token=payment-security-test-service-credential-32",
            "payment.execution.recovery-enabled=false",
            "payment.refunds.execution-enabled=false",
            "payment.refunds.recovery.initial-delay=PT1H",
            "payment.dev.payment-scenario.enabled=true"
        })
@AutoConfigureMockMvc
class PaymentSecurityIntegrationTest {
    static final String TOKEN = "payment-security-test-service-credential-32";
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("기본 관측 비활성 상태로 기동하고 토큰 없는 probe를 제공한다")
    void defaultStartupAndAnonymousHealthProbes() throws Exception {
        assertThat(context.getBeansOfType(PaymentObservation.class)).isEmpty();
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("결제와 환불 내부 API는 서비스 토큰을 요구하고 인증 뒤 입력을 검증한다")
    void serviceTokenIsRequiredBeforeBodyValidation() throws Exception {
        for (String route : new String[] {"/v1/internal/payments", "/v1/internal/refunds"}) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(
                            post(route)
                                    .header("X-Service-Token", TOKEN)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("주문 종료 API는 올바른 Core 서비스 토큰만 허용한다")
    void closureRequiresCoreToken() throws Exception {
        String path = "/v1/internal/payments/1/order-closure";
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put(path)
                                .header("X-Service-Token", "wrong-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put(path)
                                .header("Authorization", "Bearer user-only-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put(path)
                                .header("X-Service-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("prod가 함께 활성화되면 개발 플래그가 있어도 시나리오 registry와 API를 만들지 않는다")
    void productionProfileCannotEnableDevScenario() throws Exception {
        assertThat(context.getBeansOfType(DevPaymentScenarioRegistry.class)).isEmpty();
        mvc.perform(
                        put("/v1/internal/payment-scenarios/PG-1")
                                .header("X-Service-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"scenario\":\"INSTANT_SUCCESS\"}"))
                .andExpect(status().isNotFound());
    }
}
