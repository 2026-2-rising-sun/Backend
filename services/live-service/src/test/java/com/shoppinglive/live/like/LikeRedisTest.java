package com.shoppinglive.live.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.like.application.LikeService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 Redis 로 좋아요 증가를 검증한다.
 * 기동: docker run -d --name live-redis-test -p 56379:6379 redis:7-alpine
 * 실행: LIVE_REDIS_TEST=1 ./gradlew :services:live-service:test --tests '*LikeRedisTest'
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("좋아요 증가 (실제 Redis)")
class LikeRedisTest extends LiveSecuritySupport {
    @Autowired MockMvc mvc;
    @Autowired BroadcastService broadcasts;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getenv().getOrDefault("LIVE_REDIS_PORT", "56379"));
    }

    /** 테스트 DB 는 매번 id 1 부터 시작한다. 오래 떠 있는 Redis 에 남은 이전 실행의 값을 지운다. */
    private long liveBroadcast(final String name) {
        final long id = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name,
            Instant.parse("2026-10-03T11:00:00Z"), "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
            "https://example.com/" + name + ".m3u8")).getId();
        jdbc.update("UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        redis.delete(LikeService.key(id));
        return id;
    }

    private void storeSnapshot(final long id, final long total) {
        jdbc.update("INSERT INTO broadcast_like_snapshot (broadcast_id, total, updated_at) "
            + "VALUES (?, ?, CURRENT_TIMESTAMP)", id, total);
    }

    @DisplayName("좋아요는 요청마다 1 증가하고 증가 직후 합계를 반환하며 같은 회원의 반복과 ADMIN 을 허용한다")
    @Test
    void eachLikeAddsOne() throws Exception {
        final long id = liveBroadcast("like-add");

        mvc.perform(get("/v1/broadcasts/{id}/likes", id)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", userBearer()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.broadcastId").value(id))
            .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", userBearer()))
            .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", adminBearer()))
            .andExpect(jsonPath("$.data.total").value(3));

        mvc.perform(get("/v1/broadcasts/{id}/likes", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(3));
        // 만료는 7일이다. 방송 종료 뒤에도 조회와 마지막 보관이 가능해야 한다.
        assertThat(redis.getExpire(LikeService.key(id))).isBetween(7 * 86400L - 60, 7 * 86400L);
    }

    @DisplayName("동시 좋아요 200건의 증가분이 모두 합산되고 응답 합계가 서로 겹치지 않는다")
    @Test
    void concurrentLikesAreAllCounted() throws Exception {
        final long id = liveBroadcast("like-concurrent");
        final String bearer = userBearer();
        final Callable<Integer> like = () -> ((Number) com.jayway.jsonpath.JsonPath.read(
            mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.total")).intValue();

        final List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int request = 0; request < 200; request++) {
                results.add(pool.submit(like));
            }
        }

        final List<Integer> totals = new ArrayList<>();
        for (final Future<Integer> result : results) {
            totals.add(result.get());
        }
        assertThat(totals).doesNotHaveDuplicates().hasSize(200).contains(200);
        assertThat(redis.opsForValue().get(LikeService.key(id))).isEqualTo("200");
    }

    @DisplayName("Redis 에 값이 없으면 DB 보관 합계에서 시작하고, 값이 있으면 DB 값으로 덮어쓰지 않는다")
    @Test
    void redisValueStartsFromTheSnapshotAndIsNeverOverwritten() throws Exception {
        final long restored = liveBroadcast("like-restored");
        storeSnapshot(restored, 100);
        mvc.perform(post("/v1/broadcasts/{id}/likes", restored).header("Authorization", userBearer()))
            .andExpect(jsonPath("$.data.total").value(101));

        final long ahead = liveBroadcast("like-ahead");
        storeSnapshot(ahead, 100);
        redis.opsForValue().set(LikeService.key(ahead), "500");
        mvc.perform(get("/v1/broadcasts/{id}/likes", ahead)).andExpect(jsonPath("$.data.total").value(500));
        mvc.perform(post("/v1/broadcasts/{id}/likes", ahead).header("Authorization", userBearer()))
            .andExpect(jsonPath("$.data.total").value(501));
    }

    @DisplayName("LIVE 가 아닌 방송의 좋아요는 409 이고 합계가 바뀌지 않는다")
    @Test
    void endedBroadcastIsNotCounted() throws Exception {
        final long id = liveBroadcast("like-ended");
        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", userBearer()))
            .andExpect(jsonPath("$.data.total").value(1));
        jdbc.update("UPDATE broadcast SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP WHERE id = ?", id);

        mvc.perform(post("/v1/broadcasts/{id}/likes", id).header("Authorization", userBearer()))
            .andExpect(status().isConflict());

        mvc.perform(get("/v1/broadcasts/{id}/likes", id)).andExpect(jsonPath("$.data.total").value(1));
    }
}
