package com.shoppinglive.commerce.refunds;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
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
    void rejectsUnknownFieldsInsteadOfTreatingThemAsWholeGroupRequest() throws Exception {
        mvc.perform(post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                .header("Authorization", bearer(MEMBER_A))
                .header("Idempotency-Key", "unknown-refund-field")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cartItemId\":101,\"quantity\":1}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNullOrMissingCartIdsWhenARequestBodyIsPresent() throws Exception {
        mvc.perform(post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                .header("Authorization", bearer(MEMBER_A))
                .header("Idempotency-Key", "null-cart-ids")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cartItemIds\":null}"))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                .header("Authorization", bearer(MEMBER_A))
                .header("Idempotency-Key", "missing-cart-ids")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsJsonNullWithoutTreatingItAsAnOmittedBody() throws Exception {
        mvc.perform(post("/v1/payment-groups/{number}/refunds", "PG-unknown")
                .header("Authorization", bearer(MEMBER_A))
                .header("Idempotency-Key", "json-null-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
            .andExpect(status().isBadRequest());
    }
}
