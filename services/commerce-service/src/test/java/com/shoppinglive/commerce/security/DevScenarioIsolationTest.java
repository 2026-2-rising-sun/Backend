package com.shoppinglive.commerce.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.payments.application.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "commerce.dev.payment-scenario.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DevScenarioIsolationTest extends CommerceSecurityTestSupport {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;

    @Test
    void externalDevCannotEnableScenarioEvenWithFlagAndAdminToken() throws Exception {
        assertThat(context.getBeansOfType(DevPaymentScenarioRegistry.class)).isEmpty();
        mvc.perform(put("/v1/dev/payment-scenarios/any").header("Authorization", adminBearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scenario\":\"INSTANT_SUCCESS\"}"))
            .andExpect(status().isForbidden());
    }
}
