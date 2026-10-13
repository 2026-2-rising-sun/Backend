package com.shoppinglive.live.promotion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("프로모션 HTTP (생성 멱등, 공개 조회, 권한)")
class PromotionHttpTest extends LiveSecuritySupport {

    @Autowired MockMvc mvc;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private long idOf(String responseBody) throws Exception {
        return mapper.readTree(responseBody).at("/data/id").asLong();
    }

    private long liveBroadcast() {
        final String name = UUID.randomUUID().toString();
        final long id = broadcasts.register(name, new BroadcastInput(name, Instant.now(),
            "arn:aws:ivs:channel/" + name, "https://example.live-video.net/" + name)).getId();
        jdbc.update("UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return id;
    }

    private String body(String type, String title, String durationLine) {
        return "{\"type\":\"" + type + "\",\"title\":\"" + title
            + "\",\"attributes\":{\"couponPolicyId\":7,\"claimLimit\":500}"
            + durationLine + "}";
    }

    @DisplayName("생성 201 — 같은 키·같은 본문 재요청은 같은 id, 다른 본문은 409")
    @Test
    void createIsIdempotent() throws Exception {
        long broadcast = liveBroadcast();
        String key = UUID.randomUUID().toString();
        String payload = body("COUPON_DROP", "할인", ",\"durationSeconds\":300");

        long firstId = idOf(mvc.perform(post("/v1/admin/broadcasts/{id}/promotions", broadcast)
                .header("Authorization", adminBearer())
                .header("Idempotency-Key", key)
                .contentType("application/json").content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("READY"))
            .andReturn().getResponse().getContentAsString());

        long replayedId = idOf(mvc.perform(post("/v1/admin/broadcasts/{id}/promotions", broadcast)
                .header("Authorization", adminBearer())
                .header("Idempotency-Key", key)
                .contentType("application/json").content(payload))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        if (replayedId != firstId) {
            throw new AssertionError("replay should return same id, was " + replayedId + " vs " + firstId);
        }

        mvc.perform(post("/v1/admin/broadcasts/{id}/promotions", broadcast)
                .header("Authorization", adminBearer())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body("TIME_DEAL", "다른 제목", ",\"durationSeconds\":300")))
            .andExpect(status().isConflict());
    }

    @DisplayName("잘못된 type 은 400, USER 토큰은 403")
    @Test
    void validationAndAuthorization() throws Exception {
        long broadcast = liveBroadcast();

        mvc.perform(post("/v1/admin/broadcasts/{id}/promotions", broadcast)
                .header("Authorization", adminBearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(body("lowercase", "제목", "")))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/admin/broadcasts/{id}/promotions", broadcast)
                .header("Authorization", userBearer())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content(body("COUPON_DROP", "제목", "")))
            .andExpect(status().isForbidden());
    }

    @DisplayName("공개 조회는 비회원도 200, OPEN 만 노출")
    @Test
    void publicListShowsOnlyOpen() throws Exception {
        long broadcast = liveBroadcast();
        String payload = body("COUPON_DROP", "할인", "");
        long promotionId = idOf(mvc.perform(
                post("/v1/admin/broadcasts/{id}/promotions", broadcast)
                    .header("Authorization", adminBearer())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json").content(payload))
            .andReturn().getResponse().getContentAsString());

        // READY 는 공개 조회에 안 보인다
        mvc.perform(get("/v1/broadcasts/{id}/promotions", broadcast))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0));

        mvc.perform(post("/v1/admin/broadcasts/{id}/promotions/{promotionId}/open",
                broadcast, promotionId).header("Authorization", adminBearer()))
            .andExpect(status().isOk());

        mvc.perform(get("/v1/broadcasts/{id}/promotions", broadcast))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(promotionId));

        mvc.perform(post("/v1/admin/broadcasts/{id}/promotions/{promotionId}/close",
                broadcast, promotionId).header("Authorization", adminBearer()))
            .andExpect(status().isOk());

        mvc.perform(get("/v1/broadcasts/{id}/promotions", broadcast))
            .andExpect(jsonPath("$.data.length()").value(0));
    }
}
