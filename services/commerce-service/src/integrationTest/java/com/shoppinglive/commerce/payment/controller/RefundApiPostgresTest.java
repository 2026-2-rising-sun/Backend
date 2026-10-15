package com.shoppinglive.commerce.payment.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.payment.service.RefundTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "commerce.refunds.api-enabled=true")
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
@AutoConfigureMockMvc
class RefundApiPostgresTest extends RefundTestSupport {
    @Autowired MockMvc mvc;
    @Autowired com.shoppinglive.commerce.payment.service.RefundExecutionService executions;
    @Autowired com.shoppinglive.commerce.payment.service.DurableMockRefundGateway refundGateway;

    @DisplayName("회원과 판매자는 각자에게 속한 환불 정보만 조회한다")
    @Test
    void memberAndSellersSeeOnlyTheirOwnRefundProjection() throws Exception {
        var group = createAndPay();
        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", group.groupNumber())
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "refund-http-all"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.refundAmount").value(25000))
                .andExpect(jsonPath("$.data.cumulativeRefundAmount").value(0))
                .andExpect(jsonPath("$.data.targets.length()").value(2));

        mvc.perform(
                        post("/v1/payment-groups/{number}/refunds", group.groupNumber())
                                .header("Authorization", bearer(MEMBER_A))
                                .header("Idempotency-Key", "refund-http-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundAmount").value(25000))
                .andExpect(jsonPath("$.data.cumulativeRefundAmount").value(0));

        mvc.perform(
                        get("/v1/payment-groups/{number}/refunds", group.groupNumber())
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refundAmount").value(25000))
                .andExpect(jsonPath("$.data[0].cumulativeRefundAmount").value(0));

        mvc.perform(get("/v1/seller/refunds").header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refundAmount").value(20000))
                .andExpect(jsonPath("$.data[0].cumulativeRefundAmount").value(0))
                .andExpect(jsonPath("$.data[0].targets.length()").value(1))
                .andExpect(jsonPath("$.data[0].targets[0].cartItemId").value(a.getId()))
                .andExpect(jsonPath("$.data[0].paymentGroupNumber").doesNotExist());

        mvc.perform(get("/v1/seller/refunds").header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isForbidden());

        String sellerBToken = "Bearer " + TOKENS.token(SELLER_B, java.util.Set.of("SELLER"));
        mvc.perform(get("/v1/seller/refunds").header("Authorization", sellerBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refundAmount").value(5000))
                .andExpect(jsonPath("$.data[0].cumulativeRefundAmount").value(0))
                .andExpect(jsonPath("$.data[0].targets.length()").value(1))
                .andExpect(jsonPath("$.data[0].targets[0].cartItemId").value(b.getId()));
    }

    @DisplayName("본인 미확정 환불을 조회하되 키·작업 임대·다른 판매자 금액은 노출하지 않는다")
    @Test
    void ownUnknownRecoveryIsVisibleWithoutLeakingKeysLeasesOrAnotherSellersMoney()
            throws Exception {
        var group = createAndPay();
        var request =
                mvc.perform(
                                post("/v1/payment-groups/{number}/refunds", group.groupNumber())
                                        .header("Authorization", bearer(MEMBER_A))
                                        .header("Idempotency-Key", "refund-http-recovery"))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(request.getResponse().getContentAsString())
                        .path("data")
                        .path("id")
                        .asLong();
        jdbc.update(
                "UPDATE refund_request SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at,"
                        + "next_action_at=clock_timestamp()+INTERVAL '1 minute' WHERE id=?",
                id);

        mvc.perform(
                        get("/v1/payment-groups/{number}/refunds/{id}", group.groupNumber(), id)
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recovery.retryCount").value(3))
                .andExpect(jsonPath("$.data.recovery.retryExhausted").value(true))
                .andExpect(jsonPath("$.data.recovery.nextActionAt").isString())
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
        mvc.perform(
                        get("/v1/payment-groups/{number}/refunds/{id}", group.groupNumber(), id)
                                .header("Authorization", bearer(MEMBER_B)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/seller/refunds/{id}", id).header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refundAmount").value(20000))
                .andExpect(jsonPath("$.data.targets.length()").value(1))
                .andExpect(jsonPath("$.data.recovery.retryExhausted").value(true))
                .andExpect(jsonPath("$.data.paymentGroupNumber").doesNotExist())
                .andExpect(jsonPath("$.data.leaseToken").doesNotExist());
        String unrelated =
                "Bearer "
                        + TOKENS.token(
                                "66666666-6666-4666-8666-666666666666", java.util.Set.of("SELLER"));
        mvc.perform(get("/v1/seller/refunds/{id}", id).header("Authorization", unrelated))
                .andExpect(status().isNotFound());
        refundGateway.execute(id, 25000);
        makeRefundDue(id);
        org.assertj.core.api.Assertions.assertThat(executions.execute(id)).isTrue();
        mvc.perform(
                        get("/v1/payment-groups/{number}/refunds/{id}", group.groupNumber(), id)
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.recovery").value(org.hamcrest.Matchers.nullValue()));
    }
}
