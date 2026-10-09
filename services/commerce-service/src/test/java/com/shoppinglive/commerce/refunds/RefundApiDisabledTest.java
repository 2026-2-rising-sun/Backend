package com.shoppinglive.commerce.refunds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RefundApiDisabledTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void refundRoutesRemainUnavailableByDefault() throws Exception {
        mvc.perform(post("/v1/payment-groups/PG-unknown/refunds")
                .header("Authorization", bearer(MEMBER_A))
                .header("Idempotency-Key", "disabled")
                .contentType("application/json").content("{}"))
            .andExpect(status().isNotFound());
    }
}
