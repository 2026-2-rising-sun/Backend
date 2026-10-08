package com.shoppinglive.live.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class LikeTest extends LiveSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;

    private long broadcast(String status) {
        final String name = UUID.randomUUID().toString();
        final long id = broadcasts.register(name, new BroadcastInput(name, Instant.now(),
            "arn:aws:ivs:ap-northeast-2:1:channel/" + name, "https://example.com/" + name)).getId();
        jdbc.update("UPDATE broadcast SET status = ? WHERE id = ?", status, id);
        return id;
    }

    private MockHttpServletRequestBuilder change(long id, String key, boolean liked) {
        return put("/v1/broadcasts/{id}/likes/mine", id).header("Authorization", userBearer())
            .header("Idempotency-Key", key).contentType("application/json").content("{\"liked\":" + liked + "}");
    }

    @Test void retryAndCancelPreserveLatestStateEvenWithAnOldReplay() throws Exception {
        final long id = broadcast("LIVE");
        final String first = UUID.randomUUID().toString();
        final String cancel = UUID.randomUUID().toString();
        mvc.perform(get("/v1/broadcasts/{id}/likes", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(get("/v1/broadcasts/{id}/likes/mine", id).header("Authorization", userBearer()))
            .andExpect(jsonPath("$.data.liked").value(false)).andExpect(jsonPath("$.data.stateVersion").value(0));
        final String original = mvc.perform(change(id, first, true)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.version").value(1))
            .andReturn().getResponse().getContentAsString();
        assertThat(mvc.perform(change(id, first, true)).andReturn().getResponse().getContentAsString()).isEqualTo(original);
        mvc.perform(change(id, UUID.randomUUID().toString(), true)).andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.stateVersion").value(1)).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(change(id, cancel, false)).andExpect(jsonPath("$.data.total").value(0))
            .andExpect(jsonPath("$.data.stateVersion").value(2)).andExpect(jsonPath("$.data.version").value(2));
        assertThat(mvc.perform(change(id, first, true)).andReturn().getResponse().getContentAsString()).isEqualTo(original);
        mvc.perform(get("/v1/broadcasts/{id}/likes/mine", id).header("Authorization", userBearer()))
            .andExpect(jsonPath("$.data.liked").value(false)).andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(change(id, first, false)).andExpect(status().isConflict());
    }

    @Test void endedReplayIsAllowedButNewRequestsAreRejected() throws Exception {
        final long id = broadcast("LIVE");
        final String key = UUID.randomUUID().toString();
        mvc.perform(change(id, key, true)).andExpect(status().isOk());
        broadcasts.end(id);
        mvc.perform(change(id, key, true)).andExpect(status().isOk());
        mvc.perform(change(id, UUID.randomUUID().toString(), false)).andExpect(status().isConflict());
        mvc.perform(get("/v1/broadcasts/{id}/likes/mine", id).header("Authorization", userBearer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.liked").value(true));
        mvc.perform(change(broadcast("PREPARING"), UUID.randomUUID().toString(), true)).andExpect(status().isConflict());
        mvc.perform(change(Long.MAX_VALUE, UUID.randomUUID().toString(), true)).andExpect(status().isNotFound());
    }

    @Test void validationAndSecurityUseTheExistingErrorEnvelope() throws Exception {
        final long id = broadcast("LIVE");
        mvc.perform(put("/v1/broadcasts/{id}/likes/mine", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/broadcasts/{id}/likes/mine", id)).andExpect(status().isUnauthorized());
        mvc.perform(change(id, "bad", true)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(change(id, "", true)).andExpect(status().isBadRequest());
        mvc.perform(change(id, UUID.randomUUID().toString(), true).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(change(id, UUID.randomUUID().toString(), true).content("{\"liked\":null}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/broadcasts/{id}/likes/mine", id).header("Authorization", userBearer())
            .contentType("application/json").content("{\"liked\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/broadcasts/{id}/likes/mine", id).header("Authorization", adminBearer())
            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType("application/json")
            .content("{\"liked\":true}"))
            .andExpect(status().isOk());
        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", userBearer()))
            .andExpect(status().isForbidden());
        accessSessions.fail();
        mvc.perform(change(id, UUID.randomUUID().toString(), true)).andExpect(status().isServiceUnavailable());
    }

    @Test void legacySnapshotsAreIgnoredEvenWithoutRedis() throws Exception {
        final long id = broadcast("LIVE");
        jdbc.update("INSERT INTO broadcast_like_snapshot (broadcast_id, total, updated_at) VALUES (?, 1284, CURRENT_TIMESTAMP)", id);
        mvc.perform(get("/v1/broadcasts/{id}/likes", id)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(change(id, UUID.randomUUID().toString(), true)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1));
    }
}
