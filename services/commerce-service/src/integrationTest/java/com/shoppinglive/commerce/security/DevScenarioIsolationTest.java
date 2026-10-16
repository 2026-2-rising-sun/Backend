package com.shoppinglive.commerce.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.DisplayName;
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

    @DisplayName("dev 환경은 플래그와 판매자 토큰이 있어도 결제 시나리오를 활성화할 수 없다")
    @Test
    void externalDevCannotEnableScenarioEvenWithFlagAndSellerToken() throws Exception {
        assertThat(context.getBeansOfType(DevPaymentScenarioRegistry.class)).isEmpty();
        mvc.perform(
                        put("/v1/dev/payment-scenarios/any")
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"scenario\":\"INSTANT_SUCCESS\"}"))
                .andExpect(status().isForbidden());
    }
}
