package com.shoppinglive.live.like;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 테스트 설정의 Redis 포트는 닫혀 있다. 권한·상태 검사와 Redis 장애 시 동작을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("좋아요 권한·상태와 Redis 장애 시 동작")
class LikeTest extends LiveSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;

    private long broadcast(final String name, final String status) {
        final long id = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name,
            Instant.parse("2026-10-03T11:00:00Z"), "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
            "https://example.com/" + name + ".m3u8")).getId();
        jdbc.update("UPDATE broadcast SET status = ?, started_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
        return id;
    }

    @DisplayName("비회원의 좋아요는 401 이고 인증 상태를 확인할 수 없으면 503 이다")
    @Test
    void anonymousLikeIsRejected() throws Exception {
        final long id = broadcast("like-anonymous", "LIVE");

        mvc.perform(post("/v1/broadcasts/{id}/likes", id)).andExpect(status().isUnauthorized());
        accessSessions.fail();
        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", userBearer()))
            .andExpect(status().isServiceUnavailable());
    }

    @DisplayName("LIVE 가 아닌 방송은 409, 없는 방송은 404, 숫자가 아닌 id 는 400 이다")
    @Test
    void likeRequiresALiveBroadcast() throws Exception {
        for (final String status : new String[] {"PREPARING", "ENDED"}) {
            mvc.perform(post("/v1/broadcasts/{id}/likes", broadcast("like-" + status.toLowerCase(), status))
                    .header("Authorization", userBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        }
        mvc.perform(post("/v1/broadcasts/{id}/likes", Long.MAX_VALUE).header("Authorization", userBearer()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/v1/broadcasts/abc/likes").header("Authorization", userBearer()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/broadcasts/{id}/likes", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/broadcasts/abc/likes")).andExpect(status().isBadRequest());
    }

    @DisplayName("Redis 장애 시 좋아요는 503 이고 조회는 마지막 DB 보관 합계로 응답한다")
    @Test
    void redisOutageRejectsLikeAndServesTheStoredTotal() throws Exception {
        final long stored = broadcast("like-stored", "LIVE");
        final long none = broadcast("like-none", "PREPARING");
        jdbc.update("INSERT INTO broadcast_like_snapshot (broadcast_id, total, updated_at) "
            + "VALUES (?, 1284, CURRENT_TIMESTAMP)", stored);

        mvc.perform(post("/v1/broadcasts/{id}/likes", stored).header("Authorization", userBearer()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        mvc.perform(get("/v1/broadcasts/{id}/likes", stored))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.broadcastId").value(stored))
            .andExpect(jsonPath("$.data.total").value(1284));
        mvc.perform(get("/v1/broadcasts/{id}/likes", none))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(0));
    }
}
