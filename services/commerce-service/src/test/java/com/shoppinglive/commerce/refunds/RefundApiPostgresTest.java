package com.shoppinglive.commerce.refunds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "commerce.refunds.api-enabled=true")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
@AutoConfigureMockMvc
class RefundApiPostgresTest extends RefundTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void memberAndSellersSeeOnlyTheirOwnRefundProjection() throws Exception {
        var group = createAndPay();
        mvc.perform(post("/v1/payment-groups/{number}/refunds", group.groupNumber())
                .header("Authorization", bearer(MEMBER_A)).header("Idempotency-Key", "refund-http-all"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.refundAmount").value(25000))
            .andExpect(jsonPath("$.data.cumulativeRefundAmount").value(0))
            .andExpect(jsonPath("$.data.targets.length()").value(2));

        mvc.perform(post("/v1/payment-groups/{number}/refunds", group.groupNumber())
                .header("Authorization", bearer(MEMBER_A)).header("Idempotency-Key", "refund-http-all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refundAmount").value(25000))
            .andExpect(jsonPath("$.data.cumulativeRefundAmount").value(0));

        mvc.perform(get("/v1/payment-groups/{number}/refunds", group.groupNumber())
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
}
