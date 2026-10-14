package com.shoppinglive.member.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
class ApiDocumentationIntegrationTest extends com.shoppinglive.member.MemberAuthTestSupport {
    @Autowired private MockMvc documentation;

    @Test
    @DisplayName("컨트롤러에서 생성한 API 명세와 인증 방식을 제공한다")
    void generatesDocumentationFromControllers() throws Exception {
        documentation
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Member Service API"))
                .andExpect(jsonPath("$.info.version").value("0.3.0"))
                .andExpect(jsonPath("$.paths['/v1/members/me']").exists())
                .andExpect(
                        jsonPath("$.components.securitySchemes.BearerAuth.scheme").value("bearer"));
    }

    @Test
    @DisplayName("Swagger 화면 진입 경로를 제공한다")
    void servesSwaggerUi() throws Exception {
        documentation
                .perform(get("/swagger"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        documentation.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        documentation
                .perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        documentation.perform(get("/swagger-ui.html")).andExpect(status().is4xxClientError());
    }
}
