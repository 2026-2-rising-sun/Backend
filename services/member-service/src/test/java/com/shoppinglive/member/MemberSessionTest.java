package com.shoppinglive.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.member.auth.application.MemberLoginService;
import com.shoppinglive.member.auth.application.MemberSessionService;
import com.shoppinglive.member.auth.application.MemberTokenIssuer;
import com.shoppinglive.member.auth.application.TokenPair;
import com.shoppinglive.member.auth.infrastructure.RefreshSessionRepository;
import com.shoppinglive.member.members.application.MemberService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

class MemberSessionTest extends MemberAuthTestSupport {
    @Autowired protected MemberService memberService;
    @Autowired protected MemberLoginService loginService;
    @Autowired protected MemberSessionService sessionService;
    @MockitoSpyBean MemberTokenIssuer issuer;
    @MockitoSpyBean RefreshSessionRepository sessions;

    @Test
    void rotationKeepsAbsoluteExpiryAndStoresOnlyHashes() throws Exception {
        var initial = account("a@example.com");
        var expiry = jdbc.queryForObject("SELECT expires_at FROM refresh_families", Timestamp.class);
        var next = rotated(initial.refreshToken());
        assertThat(next.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(family(next)).isEqualTo(family(initial));
        assertThat(next.expiresIn()).isEqualTo(900);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM refresh_families", Timestamp.class)).isEqualTo(expiry);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE used_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class))
            .containsExactlyInAnyOrder(RefreshSessionRepository.hash(initial.refreshToken()), RefreshSessionRepository.hash(next.refreshToken()));
        profile(next).andExpect(status().isOk());
    }

    @Test
    void knownTokenReuseCommitsSecurityRevocationButForgedTokenDoesNot() throws Exception {
        var initial = account("a@example.com");
        var independent = loginService.login("a@example.com", "password123");
        refresh(family(initial) + ".forged").andExpect(status().isUnauthorized());
        profile(initial).andExpect(status().isOk());
        var next = rotated(initial.refreshToken());
        refresh(initial.refreshToken()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT security_revoked_at FROM refresh_families WHERE id=?", Timestamp.class, family(initial))).isNotNull();
        profile(initial).andExpect(status().isUnauthorized());
        profile(next).andExpect(status().isUnauthorized());
        refresh(next.refreshToken()).andExpect(status().isUnauthorized());
        profile(independent).andExpect(status().isOk());
    }

    @Test
    void ordinaryLogoutIsIdempotentAndOnlyDisablesItsRefreshFamily() throws Exception {
        var initial = account("a@example.com");
        var independent = loginService.login("a@example.com", "password123");
        for (String token : new String[] {initial.refreshToken(), initial.refreshToken(), "unknown-token"}) {
            jsonPost("/v1/auth/logout", Map.of("refreshToken", token)).andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        refresh(initial.refreshToken()).andExpect(status().isUnauthorized());
        profile(initial).andExpect(status().isOk());
        profile(independent).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT security_revoked_at FROM refresh_families WHERE id=?", Timestamp.class, family(initial))).isNull();
    }

    @Test
    void refreshExpiryDoesNotRevokeAccessIssuedNearTheAbsoluteBoundary() throws Exception {
        var token = account("a@example.com");
        jdbc.update("UPDATE refresh_families SET created_at=?,expires_at=? WHERE id=?",
            Timestamp.from(Instant.now().minusSeconds(31 * 86400L)), Timestamp.from(Instant.now().minusSeconds(1)), family(token));
        refresh(token.refreshToken()).andExpect(status().isUnauthorized());
        profile(token).andExpect(status().isOk());
    }

    @Test
    void withdrawalRequiresPasswordThenRevokesEveryFamilyAndKeepsEmailReserved() throws Exception {
        var token = account("a@example.com");
        var other = loginService.login("a@example.com", "password123");
        withdrawal(token, "incorrect").andExpect(status().isUnauthorized());
        profile(token).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM members", Timestamp.class)).isNull();
        withdrawal(token, "password123").andExpect(status().isNoContent());
        profile(token).andExpect(status().isUnauthorized());
        profile(other).andExpect(status().isUnauthorized());
        refresh(other.refreshToken()).andExpect(status().isUnauthorized());
        jsonPost("/v1/auth/login", Map.of("email", "a@example.com", "password", "password123")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup("a@example.com")))
            .andExpect(status().isConflict());
        assertThat(members.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_families WHERE security_revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void sellerCanRevokeOnlyOwnSessionsAndNewLoginRemainsPossible() throws Exception {
        var target = account("a@example.com");
        account("seller@example.com");
        jdbc.update("UPDATE members SET role='SELLER' WHERE email='seller@example.com'");
        var seller = loginService.login("seller@example.com", "password123");
        var seller2 = loginService.login("seller@example.com", "password123");
        UUID targetId = members.findByEmail("a@example.com").orElseThrow().getId();
        UUID sellerId = members.findByEmail("seller@example.com").orElseThrow().getId();
        String route = "/v1/admin/members/" + sellerId + "/sessions/revoke";
        mvc.perform(post(route)).andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("Authorization", bearer(target))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/admin/members/" + targetId + "/sessions/revoke")
            .header("Authorization", bearer(seller))).andExpect(status().isForbidden());
        profile(target).andExpect(status().isOk());
        mvc.perform(post(route).header("Authorization", bearer(seller))).andExpect(status().isNoContent());
        profile(seller).andExpect(status().isUnauthorized());
        profile(seller2).andExpect(status().isUnauthorized());
        profile(loginService.login("seller@example.com", "password123")).andExpect(status().isOk());
    }

    @Test
    void internalStatusChecksRequireSeparateAllowedCallerAndMatchMemberToFamily() throws Exception {
        var token = account("a@example.com");
        UUID memberId = members.findByEmail("a@example.com").orElseThrow().getId();
        String body = mapper.writeValueAsString(Map.of("memberId", memberId, "sessionId", family(token)));
        String route = "/v1/internal/auth/sessions/check";
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(route).header("X-Service-Token", "test-other-member-credential-0123456789")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        for (String caller : new String[] {"shopping", "commerce", "live"}) {
            check(caller, memberId, family(token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
        }
        check("shopping", UUID.randomUUID(), family(token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        check("shopping", memberId, UUID.randomUUID()).andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
    }

    @Test
    void stateStorageFailureReturns503ForUserAndCallerWithoutBypassingAuthentication() throws Exception {
        var token = account("a@example.com");
        UUID memberId = members.findByEmail("a@example.com").orElseThrow().getId();
        doThrow(new DataAccessResourceFailureException("test storage down")).when(sessions).isAccessActive(any(), any());
        profile(token).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        check("shopping", memberId, family(token)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void refreshSigningAndStorageFailuresRollBackConsumptionAndDoNotExposeTokens() throws Exception {
        var token = account("a@example.com");
        doThrow(new JwtEncodingException("test signing failure")).when(issuer).issue(any(), any(), any());
        refresh(token.refreshToken()).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data").isEmpty());
        assertSingleUnusedToken();
        doCallRealMethod().when(issuer).issue(any(), any(), any());
        doAnswer(invocation -> { invocation.callRealMethod(); throw new DataAccessResourceFailureException("test write failure"); })
            .when(sessions).rotate(any(), any(), any());
        refresh(token.refreshToken()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").isEmpty());
        assertSingleUnusedToken();
        doCallRealMethod().when(sessions).rotate(any(), any(), any());
        profile(rotated(token.refreshToken())).andExpect(status().isOk());
    }

    @Test
    void withdrawalStorageFailureRollsBackMemberAndAllSessionChanges() throws Exception {
        var token = account("a@example.com");
        doAnswer(invocation -> { invocation.callRealMethod(); throw new DataAccessResourceFailureException("test write failure"); })
            .when(sessions).revokeAll(any(), any());
        withdrawal(token, "password123").andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM members", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT security_revoked_at FROM refresh_families", Timestamp.class)).isNull();
        profile(token).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidInputAndIdentityInjectionWithoutChangingSessions() throws Exception {
        var token = account("a@example.com");
        for (String route : new String[] {"/v1/auth/refresh", "/v1/auth/logout"}) {
            jsonPost(route, Map.of("refreshToken", " ")).andExpect(status().isBadRequest());
            jsonPost(route, Map.of("refreshToken", token.refreshToken(), "memberId", UUID.randomUUID())).andExpect(status().isBadRequest());
        }
        withdrawal(token, "가".repeat(25)).andExpect(status().isBadRequest());
        assertSingleUnusedToken();
    }

    protected TokenPair account(String email) {
        memberService.register(email, "password123", "member");
        return loginService.login(email, "password123");
    }
    protected UUID family(TokenPair token) { return UUID.fromString(token.refreshToken().split("\\.")[0]); }
    protected String bearer(TokenPair token) { return "Bearer " + token.accessToken(); }
    protected ResultActions profile(TokenPair token) throws Exception { return mvc.perform(get("/v1/members/me").header("Authorization", bearer(token))); }
    protected ResultActions refresh(String token) throws Exception { return jsonPost("/v1/auth/refresh", Map.of("refreshToken", token)); }
    protected TokenPair rotated(String token) throws Exception {
        var response = refresh(token).andExpect(status().isOk()).andReturn().getResponse();
        return mapper.treeToValue(mapper.readTree(response.getContentAsString()).path("data"), TokenPair.class);
    }
    private ResultActions withdrawal(TokenPair token, String password) throws Exception {
        return mvc.perform(delete("/v1/members/me").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("password", password))));
    }
    private ResultActions check(String caller, UUID memberId, UUID familyId) throws Exception {
        return mvc.perform(post("/v1/internal/auth/sessions/check").header("X-Service-Token", "test-" + caller + "-member-credential-0123456789")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("memberId", memberId, "sessionId", familyId))));
    }
    private ResultActions jsonPost(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    private void assertSingleUnusedToken() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_at FROM refresh_tokens", Timestamp.class)).isNull();
    }
}
