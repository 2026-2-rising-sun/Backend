package com.shoppinglive.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

class PaymentSecurityIntegrationTest extends PaymentApiTestSupport {
    static final String WRONG = "wrong-payment-api-test-service-credential";

    @ParameterizedTest
    @ValueSource(strings = {"", WRONG})
    @DisplayName("[PAY-AUTH-01] 서비스 토큰이 없거나 다르면 결제 생성·결제창·조회·승인 경로 모두 401이다")
    void missingOrWrongTokenIsUnauthorized(String token) throws Exception {
        var requests =
                java.util.List.of(
                        post("/v1/internal/payments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"),
                        post("/v1/internal/payments/OD-123456/checkouts"),
                        get("/v1/internal/payments/OD-123456"),
                        post("/v1/internal/payments/OD-123456/approval")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"));
        for (var request : requests) {
            if (!token.isEmpty()) {
                request.header("X-Service-Token", token);
            }
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    @DisplayName("올바른 서비스 토큰이면 허용 경로가 컨트롤러까지 간다")
    void validTokenReachesControllers() throws Exception {
        mvc.perform(
                        post("/v1/internal/payments")
                                .header("X-Service-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        query("OD-NOPE01")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_FOUND"));
        checkout("OD-NOPE01")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_READY"));
    }

    @Test
    @DisplayName("허용 목록 밖의 내부 경로는 올바른 토큰이어도 403이다")
    void otherInternalPathsAreForbidden() throws Exception {
        mvc.perform(
                        post("/v1/internal/refunds")
                                .header("X-Service-Token", TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/v1/internal/payments/OD-123456/pg-calls")
                                .header("X-Service-Token", TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("liveness·readiness probe는 토큰 없이 200이다")
    void anonymousHealthProbes() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
