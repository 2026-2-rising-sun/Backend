package com.shoppinglive.member.members.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.member.security.MemberAuthTestSupport;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class MemberControllerTest extends MemberAuthTestSupport {
    @MockitoSpyBean(name = "dbHealthContributor")
    HealthContributor databaseHealth;

    @DisplayName("회원 UUID와 상속한 감사 시각을 저장하고 다시 조회한다")
    @Test
    void persistsAndReloadsMemberUuidAndInheritedAuditTimes() {
        var member =
                com.shoppinglive.member.members.entity.Member.register(
                        "audit@example.com", "hash", "before");
        members.saveAndFlush(member);
        var loaded = members.findById(member.getId()).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(member.getId());
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @DisplayName("가입 시 이메일을 정규화하고 비밀번호 해시와 USER 역할만 저장한다")
    @Test
    void signupNormalizesEmailStoresOnlyPasswordHashAndAssignsUser() throws Exception {
        mvc.perform(
                        post("/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signup(" User@Example.com ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
        var member = members.findByEmail("user@example.com").orElseThrow();
        assertThat(member.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwords.matches("password123", member.getPasswordHash())).isTrue();
        mvc.perform(
                        post("/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signup("USER@example.com")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        assertThat(members.count()).isEqualTo(1);
    }

    @DisplayName("회원가입 요청의 역할·회원 식별자 주입을 거절한다")
    @Test
    void signupRejectsRoleAndIdentityInjection() throws Exception {
        for (String field : new String[] {"roles", "memberId"}) {
            String body =
                    mapper.writeValueAsString(
                            Map.of(
                                    "email",
                                    "user@example.com",
                                    "password",
                                    "password123",
                                    "displayName",
                                    "member",
                                    field,
                                    "SELLER"));
            mvc.perform(
                            post("/v1/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(members.count()).isZero();
    }

    @DisplayName("잘못된 비밀번호와 BCrypt UTF-8 최대 길이 초과를 거절한다")
    @Test
    void rejectsInvalidAndBcryptOverlongUtf8Password() throws Exception {
        for (String password : new String[] {"short", "가".repeat(25)}) {
            mvc.perform(
                            post("/v1/auth/signup")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            mapper.writeValueAsString(
                                                    Map.of(
                                                            "email",
                                                            "user@example.com",
                                                            "password",
                                                            password,
                                                            "displayName",
                                                            "member"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(members.count()).isZero();
    }

    @DisplayName("회원 조회는 검증된 토큰의 회원을 사용하고 표시 이름만 수정할 수 있다")
    @Test
    void profileUsesVerifiedSubjectAndAllowsOnlyDisplayName() throws Exception {
        mvc.perform(
                        post("/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signup("a@example.com")))
                .andExpect(status().isCreated());
        mvc.perform(
                        post("/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(signup("b@example.com")))
                .andExpect(status().isCreated());
        var a = members.findByEmail("a@example.com").orElseThrow();
        var b = members.findByEmail("b@example.com").orElseThrow();
        var login =
                mvc.perform(
                                post("/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"a@example.com\",\"password\":\"password123\"}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        String authorization =
                "Bearer "
                        + mapper.readTree(login.getContentAsString())
                                .path("data")
                                .path("accessToken")
                                .asText();
        mvc.perform(
                        get("/v1/members/me")
                                .header("Authorization", authorization)
                                .header("X-Member-Id", b.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(a.getId().toString()));
        mvc.perform(
                        patch("/v1/members/me")
                                .header("Authorization", authorization)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"new name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("new name"));
        mvc.perform(
                        patch("/v1/members/me")
                                .header("Authorization", authorization)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"displayName\":\"bad\",\"roles\":[\"SELLER\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(members.findById(b.getId()).orElseThrow().getDisplayName()).isEqualTo("member");
    }

    @DisplayName("인프라 probe는 유지하되 회원·다른 actuator 경로는 보호한다")
    @Test
    void preservesInfrastructureProbesButProtectsProfileAndOtherActuatorEndpoints()
            throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/members/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/members/me").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @DisplayName("DB 장애 시 세부 정보 없이 readiness만 실패하고 liveness는 유지한다")
    @Test
    void databaseHealthFailureMakesReadinessUnavailableWithoutLeakingDetailsOrFailingLiveness()
            throws Exception {
        doReturn(Health.down().withDetail("database", "sensitive-database-details").build())
                .when((HealthIndicator) databaseHealth)
                .health();
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
