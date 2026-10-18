package com.shoppinglive.commerce.payment.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "commerce.refunds.api-enabled=true")
@AutoConfigureMockMvc
class RefundApiRequestValidationTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("환불 대상 조회는 인증이 없으면 거절한다")
    void requiresAuthenticationForRefundableTargets() throws Exception {
        mvc.perform(get("/v1/payment-groups/PG-missing/refundable-targets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("환불 대상 조회는 본인 결제 묶음이 없으면 정보를 노출하지 않는다")
    void hidesMissingOrUnownedRefundableTargets() throws Exception {
        mvc.perform(
                        get("/v1/payment-groups/PG-missing/refundable-targets")
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isNotFound());
    }

    @DisplayName("알 수 없는 환불 필드는 묶음 전체 요청으로 해석하지 않고 거절한다")
    @Test
    void rejectsUnknownFieldsInsteadOfTreatingThemAsWholeGroupRequest() throws Exception {
        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "unknown-refund-field")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cartItemId\":101,\"quantity\":1}"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("환불 본문이 있으면 누락되거나 null인 장바구니 ID 목록을 거절한다")
    @Test
    void rejectsNullOrMissingCartIdsWhenARequestBodyIsPresent() throws Exception {
        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "null-cart-ids")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cartItemIds\":null}"))
                .andExpect(status().isBadRequest());

        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "missing-cart-ids")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("빈 장바구니 ID 목록은 묶음 전체 환불로 해석하지 않고 거절한다")
    @Test
    void rejectsEmptyCartIdsInsteadOfTreatingThemAsWholeGroupRefund() throws Exception {
        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "empty-cart-ids")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"cartItemIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("JSON null은 본문 생략으로 해석하지 않고 거절한다")
    @Test
    void rejectsJsonNullWithoutTreatingItAsAnOmittedBody() throws Exception {
        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "json-null-body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("null"))
                .andExpect(status().isBadRequest());
    }
}
