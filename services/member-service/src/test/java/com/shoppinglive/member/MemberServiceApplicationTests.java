package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.member.members.infrastructure.MemberRepository;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@AutoConfigureMockMvc
class MemberServiceApplicationTests extends MemberAuthTestSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected MemberRepository members;
    @Autowired PasswordEncoder passwords;
    @MockitoSpyBean(name = "dbHealthContributor") HealthContributor databaseHealth;

    @BeforeEach
    void resetMembers() { members.deleteAll(); }

    @Test
    void signupNormalizesEmailStoresOnlyPasswordHashAndAssignsUser() throws Exception {
        mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup(" User@Example.com ")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.email").value("user@example.com"))
            .andExpect(jsonPath("$.data.roles[0]").value("USER"))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist());
        var member = members.findByEmail("user@example.com").orElseThrow();
        assertThat(member.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwords.matches("password123", member.getPasswordHash())).isTrue();
        mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup("USER@example.com")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFLICT"));
        assertThat(members.count()).isEqualTo(1);
    }

    @Test
    void signupRejectsRoleAndIdentityInjection() throws Exception {
        for (String field : new String[] {"roles", "memberId"}) {
            String body = mapper.writeValueAsString(Map.of("email", "user@example.com", "password", "password123",
                "displayName", "member", field, "ADMIN"));
            mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        assertThat(members.count()).isZero();
    }

    @Test
    void rejectsInvalidAndBcryptOverlongUtf8Password() throws Exception {
        for (String password : new String[] {"short", "가".repeat(25)}) {
            mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "user@example.com", "password", password, "displayName", "member"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertThat(members.count()).isZero();
    }

    @Test
    void profileUsesVerifiedSubjectAndAllowsOnlyDisplayName() throws Exception {
        mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup("a@example.com")))
            .andExpect(status().isCreated());
        mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup("b@example.com")))
            .andExpect(status().isCreated());
        var a = members.findByEmail("a@example.com").orElseThrow();
        var b = members.findByEmail("b@example.com").orElseThrow();
        String authorization = "Bearer " + TOKENS.token(a.getId().toString(), Set.of("USER"));
        mvc.perform(get("/v1/members/me").header("Authorization", authorization).header("X-Member-Id", b.getId()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberId").value(a.getId().toString()));
        mvc.perform(patch("/v1/members/me").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"new name\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("new name"));
        mvc.perform(patch("/v1/members/me").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"bad\",\"roles\":[\"ADMIN\"]}"))
            .andExpect(status().isBadRequest());
        assertThat(members.findById(b.getId()).orElseThrow().getDisplayName()).isEqualTo("member");
    }

    @Test
    void preservesInfrastructureProbesButProtectsProfileAndOtherActuatorEndpoints() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/members/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/members/me").header("Authorization", "Bearer bad"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    protected String signup(String email) throws Exception {
        return mapper.writeValueAsString(Map.of("email", email, "password", "password123", "displayName", "member"));
    }

    @Test
    void databaseHealthFailureMakesReadinessUnavailableWithoutLeakingDetailsOrFailingLiveness() throws Exception {
        doReturn(Health.down().withDetail("database", "sensitive-database-details").build())
            .when((HealthIndicator) databaseHealth).health();
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
