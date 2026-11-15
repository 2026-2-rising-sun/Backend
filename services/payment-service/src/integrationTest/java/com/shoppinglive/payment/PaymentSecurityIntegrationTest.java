package com.shoppinglive.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "spring.datasource.url=jdbc:h2:mem:payment_security;MODE=PostgreSQL",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.kafka.bootstrap-servers=localhost:1",
            "shoppinglive.outbox.relay.enabled=false",
            "payment.core-service-token=payment-security-test-service-credential-32"
        })
@AutoConfigureMockMvc
class PaymentSecurityIntegrationTest {
    static final String TOKEN = "payment-security-test-service-credential-32";
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("서비스 토큰 없는 내부 API 요청은 401이다")
    void missingServiceTokenIsUnauthorized() throws Exception {
        mvc.perform(
                        post("/v1/internal/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("올바른 서비스 토큰이 있어도 열린 내부 경로가 없어 403이다")
    void validServiceTokenIsForbiddenWhileNoRouteIsOpen() throws Exception {
        mvc.perform(
                        post("/v1/internal/payments")
                                .header("X-Service-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/internal/payments/1").header("X-Service-Token", TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("liveness·readiness probe는 토큰 없이 200이다")
    void anonymousHealthProbes() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
