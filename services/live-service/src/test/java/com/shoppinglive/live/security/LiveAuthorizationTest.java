package com.shoppinglive.live.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LiveAuthorizationTest extends LiveSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired BroadcastRepository repository;
    @Autowired BroadcastService broadcasts;
    @Autowired ApplicationContext context;

    @Test
    void everyAdminMethodRequiresVerifiedAdministratorBeforeMutation() throws Exception {
        long before = repository.count();
        for (String route : List.of("GET /v1/admin/broadcasts", "GET /v1/admin/broadcasts/1",
            "POST /v1/admin/broadcasts", "PATCH /v1/admin/broadcasts/1", "POST /v1/admin/broadcasts/1/start",
            "POST /v1/admin/broadcasts/1/end", "GET /v1/admin/broadcasts/1/products",
            "POST /v1/admin/broadcasts/1/products", "PUT /v1/admin/broadcasts/1/products/order",
            "DELETE /v1/admin/broadcasts/1/products/1")) {
            String[] parts = route.split(" ");
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
            mvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1]).header("Authorization", userBearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        assertThat(repository.count()).isEqualTo(before);
        mvc.perform(get("/v1/admin/broadcasts").header("Authorization", adminBearer())).andExpect(status().isOk());
    }

    @Test
    void anonymousPublicQueriesAndMinimalProbesRemainAvailable() throws Exception {
        var broadcast = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput("Public auth fixture",
            Instant.now().plusSeconds(600), "arn:aws:ivs:ap-northeast-2:1:channel/auth", "https://example.com/live.m3u8"));
        for (String path : List.of("/v1/broadcasts", "/v1/broadcasts/" + broadcast.getId(),
            "/v1/broadcasts/" + broadcast.getId() + "/products",
            "/actuator/health/liveness", "/actuator/health/readiness")) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        for (String path : List.of("/actuator/health", "/actuator/info", "/actuator/env", "/v1/internal/anything")) {
            mvc.perform(get(path).header("Authorization", adminBearer())).andExpect(status().isForbidden());
        }
        mvc.perform(post("/v1/broadcasts").header("Authorization", adminBearer())).andExpect(status().isForbidden());
    }

    @Test
    void invalidJwtHeadersAndServiceKeysCannotGrantAdmin() throws Exception {
        for (String token : List.of("bad", new JwtTestTokens().token(JwtTestTokens.ADMIN, Set.of("ADMIN")),
            TOKENS.sign(TOKENS.claims(JwtTestTokens.ADMIN, Set.of("ADMIN"))
                .expirationTime(Date.from(Instant.now().minusSeconds(120))).build()),
            TOKENS.sign(TOKENS.claims(JwtTestTokens.ADMIN, Set.of("ADMIN")).issuer("other").build()),
            TOKENS.sign(TOKENS.claims(JwtTestTokens.ADMIN, Set.of("ADMIN")).audience("other").build()))) {
            mvc.perform(get("/v1/admin/broadcasts").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/v1/admin/broadcasts").header("X-Service-Token", "test-live-service-credential-000000000001"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/admin/broadcasts").header("X-Member-Id", JwtTestTokens.ADMIN).header("X-Roles", "ADMIN"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void readinessFailureIs503AndDoesNotRequireAuthentication() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally { AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC); }
    }
}
