package com.shoppinglive.member.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.member.security.MemberAuthTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("local")
@TestPropertySource(properties = "member.local-test-accounts.enabled=true")
class LocalTestAccountsTest extends MemberAuthTestSupport {
    @Autowired LocalTestAccounts fixtures;
    @Autowired JwtDecoder decoder;

    @DisplayName("짧은 로컬 비밀번호도 실제 서명 로그인으로 검증하고 반복 초기화 시 ID를 유지한다")
    @Test
    void bothShortPasswordsUseRealSignedLoginAndRepeatedInitializationPreservesIds()
            throws Exception {
        fixtures.run(new DefaultApplicationArguments());
        var ids = jdbc.queryForList("SELECT id FROM members ORDER BY email");
        fixtures.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForList("SELECT id FROM members ORDER BY email")).isEqualTo(ids);
        assertThat(members.count()).isEqualTo(2);
        for (String login : new String[] {"user", "seller"}) {
            var response =
                    mvc.perform(
                                    post("/v1/auth/login")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    mapper.writeValueAsString(
                                                            Map.of(
                                                                    "email",
                                                                    login + "@local.test",
                                                                    "password",
                                                                    login))))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse();
            var jwt =
                    decoder.decode(
                            mapper.readTree(response.getContentAsString())
                                    .path("data")
                                    .path("accessToken")
                                    .asText());
            assertThat(jwt.getClaimAsStringList("roles"))
                    .containsExactly(login.equals("user") ? "USER" : "SELLER");
            assertThat(members.findByEmail(login + "@local.test").orElseThrow().getPasswordHash())
                    .isNotEqualTo(login);
        }
        mvc.perform(
                        post("/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        "short@example.test",
                                                        "password",
                                                        "user",
                                                        "displayName",
                                                        "short"))))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("계정이 충돌하면 승격·덮어쓰기 없이 새 사용자 생성도 롤백한다")
    @Test
    void collisionDoesNotPromoteOrOverwriteAccountAndRollsBackNewUser() {
        var existing =
                members.saveAndFlush(
                        com.shoppinglive.member.members.entity.Member.register(
                                "seller@local.test",
                                passwords.encode("ordinary-password"),
                                "ordinary"));
        assertThatThrownBy(() -> fixtures.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(members.count()).isEqualTo(1);
        var preserved = members.findById(existing.getId()).orElseThrow();
        assertThat(preserved.roles()).containsExactly("USER");
        assertThat(passwords.matches("ordinary-password", preserved.getPasswordHash())).isTrue();
    }
}
