package com.shoppinglive.commerce.payment.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "commerce.refunds.api-enabled=false",
            "commerce.refunds.execution-enabled=false"
        })
@AutoConfigureMockMvc
class RefundApiDisabledTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;

    @DisplayName("운영 설정으로 환불 API 경로를 명시적으로 비활성화할 수 있다")
    @Test
    void operatorCanExplicitlyDisableRefundRoutes() throws Exception {
        mvc.perform(
                        post("/v1/payment-groups/PG-unknown/refunds")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "disabled")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isNotFound());
    }
}
