package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.member.auth.application.MemberTokenIssuer;
import com.shoppinglive.member.auth.infrastructure.RefreshSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

class MemberLoginTest extends MemberAuthTestSupport {
    @Autowired JwtDecoder decoder;
    @MockitoSpyBean MemberTokenIssuer issuer;
    @MockitoSpyBean RefreshSessionRepository sessions;

    @Test
    void actualLoginReturnsSignedAccessAndOnlyHashIsStoredForInitialRefresh() throws Exception {
        register();
        var response = login("USER@example.com", "password123").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.expiresIn").value(900)).andReturn().getResponse();
        var data = mapper.readTree(response.getContentAsString()).get("data");
        var access = decoder.decode(data.get("accessToken").asText());
        var member = members.findByEmail("user@example.com").orElseThrow();
        assertThat(access.getSubject()).isEqualTo(member.getId().toString());
        assertThat(access.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(access.getHeaders().get("kid")).isEqualTo(TOKENS.keyId());
        assertThat(Duration.between(access.getIssuedAt(), access.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        String refresh = data.get("refreshToken").asText();
        String storedHash = jdbc.queryForObject("SELECT token_hash FROM refresh_tokens", String.class);
        assertThat(storedHash).isEqualTo(RefreshSessionRepository.hash(refresh)).isNotEqualTo(refresh);
        var expiry = jdbc.queryForObject("SELECT expires_at FROM refresh_families", java.sql.Timestamp.class).toInstant();
        assertThat(Duration.between(access.getIssuedAt(), expiry)).isEqualTo(Duration.ofDays(30));
        mvc.perform(get("/v1/members/me").header("Authorization", "Bearer " + data.get("accessToken").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberId").value(member.getId().toString()));
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void unknownWrongAndLockedAccountsReturnSameGenericErrorAndFailuresCommit() throws Exception {
        register();
        String unknown = errorBody(login("missing@example.com", "password123"));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(errorBody(login("user@example.com", "wrong-password"))).isEqualTo(unknown);
        }
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM members", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT login_locked_until FROM members", java.sql.Timestamp.class)).isNotNull();
        assertThat(errorBody(login("user@example.com", "password123"))).isEqualTo(unknown);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_families", Integer.class)).isZero();
        jdbc.update("UPDATE members SET login_locked_until = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        login("user@example.com", "password123").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM members", Integer.class)).isZero();
    }

    @Test
    void signingFailureDoesNotLeaveSessionOrReturnTokens() throws Exception {
        register();
        doThrow(new JwtEncodingException("test signing failure")).when(issuer).issue(any(), any());
        login("user@example.com", "password123").andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.data").isEmpty());
        assertNoSessions();
    }

    @Test
    void expiredLockResetsOnceAndRepeatedFailuresLockTheAccountAgain() throws Exception {
        register();
        for (int attempt = 0; attempt < 5; attempt++) login("user@example.com", "wrong-password").andExpect(status().isUnauthorized());
        jdbc.update("UPDATE members SET login_locked_until = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        login("user@example.com", "wrong-password").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM members", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT login_locked_until FROM members", java.sql.Timestamp.class)).isNull();
        for (int attempt = 1; attempt < 5; attempt++) login("user@example.com", "wrong-password").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM members", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT login_locked_until FROM members", java.sql.Timestamp.class).toInstant()).isAfter(Instant.now());
        login("user@example.com", "password123").andExpect(status().isUnauthorized());
        assertNoSessions();
    }

    @Test
    void databaseFailureAfterSessionInsertRollsBackFamilyAndTokenWithoutReturningAccess() throws Exception {
        register();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("test connection failure after inserts");
        }).when(sessions).create(any(UUID.class), any(Instant.class), any(Instant.class));
        login("user@example.com", "password123").andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.data").isEmpty());
        assertNoSessions();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("email", email, "password", password))));
    }
    private String errorBody(ResultActions result) throws Exception {
        var response = result.andExpect(status().isUnauthorized()).andReturn().getResponse();
        var error = mapper.readTree(response.getContentAsString()).get("error");
        return error.get("code").asText() + ":" + error.get("message").asText();
    }
    private void register() throws Exception {
        mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup("user@example.com")))
            .andExpect(status().isCreated());
    }
    private void assertNoSessions() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_families", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
    }
}
