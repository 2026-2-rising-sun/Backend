package com.shoppinglive.member.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.member.auth.service.MemberLoginService;
import com.shoppinglive.member.members.service.MemberService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class MemberInputTypesTest extends MemberAuthTestSupport {
    @Autowired MemberService memberService;
    @Autowired MemberLoginService loginService;

    @DisplayName("가입 시 숫자 비밀번호와 문자열이 아닌 이름을 거절하고 회원을 생성하지 않는다")
    @Test
    void signupRejectsNumericPasswordAndNonStringNamesWithoutCreatingMembers() throws Exception {
        for (Map<String, Object> body :
                java.util.List.<Map<String, Object>>of(
                        Map.of(
                                "email",
                                "a@example.com",
                                "password",
                                12345678,
                                "displayName",
                                "member"),
                        Map.of(
                                "email",
                                "a@example.com",
                                "password",
                                "password123",
                                "displayName",
                                123),
                        Map.of(
                                "email",
                                "a@example.com",
                                "password",
                                "password123",
                                "displayName",
                                true))) {
            mvc.perform(
                            post("/v1/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(members.count()).isZero();
    }

    @DisplayName("잘못된 문자열 자료형은 인증 확인 전에 400으로 거절하고 회원·세션을 변경하지 않는다")
    @Test
    void invalidStringTypesAre400BeforeCredentialChecksAndDoNotMutateMemberOrSessions()
            throws Exception {
        memberService.register("a@example.com", "password123", "member");
        var token = loginService.login("a@example.com", "password123");
        String authorization = "Bearer " + token.accessToken();
        for (Object value : new Object[] {12345678, true}) {
            mvc.perform(
                            post("/v1/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            mapper.writeValueAsString(
                                                    Map.of(
                                                            "email",
                                                            "a@example.com",
                                                            "password",
                                                            value))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            mvc.perform(
                            patch("/v1/members/me")
                                    .header("Authorization", authorization)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            mapper.writeValueAsString(
                                                    Map.of("displayName", value))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            mvc.perform(
                            delete("/v1/members/me")
                                    .header("Authorization", authorization)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(Map.of("password", value))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            for (String action : new String[] {"refresh", "logout"}) {
                mvc.perform(
                                post("/v1/auth/" + action)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of("refreshToken", value))))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
            }
        }
        assertThat(members.findByEmail("a@example.com").orElseThrow().getDisplayName())
                .isEqualTo("member");
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM members", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_families", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_families WHERE revoked_at IS NOT NULL OR security_revoked_at IS NOT NULL",
                                Integer.class))
                .isZero();
        mvc.perform(get("/v1/members/me").header("Authorization", authorization))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("refreshToken", token.refreshToken()))))
                .andExpect(status().isOk());
    }
}
