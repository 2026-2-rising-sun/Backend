package com.shoppinglive.live.stream.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willCallRealMethod;
import static org.mockito.BDDMockito.willThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.like.application.LikeService;
import com.shoppinglive.live.like.application.LikeSnapshotService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 실제 Redis 와 실제 HTTP 로 좋아요 합계 전달·보관과 방송 종료 처리를 검증한다.
 * 실행: LIVE_REDIS_TEST=1 ./gradlew :services:live-service:test --tests '*LikeRealtimeRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("좋아요 합계 전달·보관과 방송 종료 (실제 Redis)")
class LikeRealtimeRedisTest extends LiveSecuritySupport {
    @LocalServerPort int port;
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastStreamRegistry registry;
    @Autowired StreamRelay relay;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LikeSnapshotService snapshots;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getenv().getOrDefault("LIVE_REDIS_PORT", "56379"));
        properties.add("live.stream.subscribe-retry", () -> "200ms");
        properties.add("live.likes.publish-interval", () -> "100ms");
        // 주기 보관은 테스트가 직접 호출한다. 뒤에서 도는 작업이 결과를 바꾸지 않게 한다.
        properties.add("live.likes.snapshot-interval", () -> "1h");
    }

    @BeforeEach
    void waitForSubscription() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!relay.subscribed() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(relay.subscribed()).isTrue();
    }

    private long broadcast(final String name, final String status, final Instant endedAt) {
        final long id = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name,
            Instant.parse("2026-10-03T11:00:00Z"), "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
            "https://example.com/" + name + ".m3u8")).getId();
        jdbc.update("UPDATE broadcast SET status = ?, started_at = CURRENT_TIMESTAMP, ended_at = ? WHERE id = ?",
            status, endedAt == null ? null : java.sql.Timestamp.from(endedAt), id);
        redis.delete(LikeService.key(id));
        return id;
    }

    private HttpResponse<String> post(final String path, final String bearer) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", bearer).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private long likesTotal(final long id) throws Exception {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/v1/broadcasts/" + id + "/likes")).build(),
            HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body()).at("/data/total").asLong();
    }

    private Long storedTotal(final long id) {
        final List<Long> totals = jdbc.queryForList(
            "SELECT total FROM broadcast_like_snapshot WHERE broadcast_id = ?", Long.class, id);
        return totals.isEmpty() ? null : totals.getFirst();
    }

    private static void ready(final SseTestClient stream) throws Exception {
        assertThat(stream.nextNonEmpty()).isEqualTo("event:stream.ready");
        assertThat(stream.nextNonEmpty()).startsWith("data:");
    }

    /** 다음 이벤트의 이름이 name 이 될 때까지 다른 이벤트를 건너뛰고 그 data 를 반환한다. */
    private JsonNode nextData(final SseTestClient stream, final String name) throws Exception {
        String line = stream.nextNonEmpty();
        while (line != null && !line.equals("event:" + name)) {
            assertThat(line).isNotEqualTo(SseTestClient.END_OF_STREAM);
            line = stream.nextNonEmpty();
        }
        assertThat(line).as("event %s", name).isNotNull();
        return json.readTree(stream.nextNonEmpty().substring(5));
    }

    @DisplayName("합계가 바뀌면 설정 주기로 likes.updated 를 전달하고, 바뀌지 않으면 전달하지 않는다")
    @Test
    void changedTotalIsDeliveredOncePerChange() throws Exception {
        final long id = broadcast("likes-realtime", "LIVE", null);

        try (SseTestClient stream = new SseTestClient(port, id)) {
            ready(stream);
            for (int request = 0; request < 3; request++) {
                assertThat(post("/v1/broadcasts/" + id + "/likes", userBearer()).statusCode()).isEqualTo(200);
            }

            JsonNode data = nextData(stream, "likes.updated");
            while (data.get("total").asLong() < 3) {
                data = nextData(stream, "likes.updated");
            }
            assertThat(data.get("broadcastId").asLong()).isEqualTo(id);
            assertThat(data.get("total").asLong()).isEqualTo(3);

            // 여러 주기가 지나도 합계가 같으면 이벤트가 없다. 다음에 오는 것은 heartbeat 다.
            Thread.sleep(500);
            registry.heartbeat();
            assertThat(stream.nextNonEmpty()).isEqualTo(":heartbeat");
        }
    }

    @DisplayName("방송을 종료하면 합계를 보관하고 broadcast.ended 전달 뒤 연결을 닫으며 새 연결은 409 다")
    @Test
    void endStoresTheTotalAndClosesConnections() throws Exception {
        final long id = broadcast("likes-end", "LIVE", null);

        try (SseTestClient stream = new SseTestClient(port, id)) {
            ready(stream);
            post("/v1/broadcasts/" + id + "/likes", userBearer());
            post("/v1/broadcasts/" + id + "/likes", userBearer());

            assertThat(post("/v1/admin/broadcasts/" + id + "/end", adminBearer()).statusCode()).isEqualTo(200);

            final JsonNode ended = nextData(stream, "broadcast.ended");
            assertThat(ended.get("broadcastId").asLong()).isEqualTo(id);
            assertThat(Instant.parse(ended.get("endedAt").asText())).isBeforeOrEqualTo(Instant.now());
            assertThat(stream.nextNonEmpty()).isEqualTo(SseTestClient.END_OF_STREAM);
        }
        assertThat(registry.connectionCount(id)).isZero();
        assertThat(storedTotal(id)).isEqualTo(2);
        assertThat(likesTotal(id)).isEqualTo(2);
        try (SseTestClient late = new SseTestClient(port, id)) {
            assertThat(late.response.statusCode()).isEqualTo(409);
        }
    }

    @DisplayName("종료 시 보관이 실패해도 종료는 성공하고 Redis 합계를 조회할 수 있으며 다음 주기에 보관한다")
    @Test
    void failedSnapshotAtEndIsRetriedByThePeriodicJob() throws Exception {
        final long id = broadcast("likes-end-failure", "LIVE", null);
        post("/v1/broadcasts/" + id + "/likes", userBearer());
        willThrow(new IllegalStateException("DB 장애")).willCallRealMethod().given(snapshots).snapshot(id);

        assertThat(post("/v1/admin/broadcasts/" + id + "/end", adminBearer()).statusCode()).isEqualTo(200);

        assertThat(storedTotal(id)).isNull();
        assertThat(likesTotal(id)).isEqualTo(1);
        snapshots.snapshotAll();
        assertThat(storedTotal(id)).isEqualTo(1);
    }

    @DisplayName("주기 보관은 LIVE 와 최근 1시간 안에 종료된 방송만 다루고 DB 합계를 줄이지 않는다")
    @Test
    void periodicSnapshotCoversRecentBroadcastsAndNeverDecreases() {
        willCallRealMethod().given(snapshots).snapshot(org.mockito.ArgumentMatchers.anyLong());
        final long live = broadcast("likes-snap-live", "LIVE", null);
        final long recent = broadcast("likes-snap-recent", "ENDED", Instant.now().minusSeconds(30 * 60));
        final long old = broadcast("likes-snap-old", "ENDED", Instant.now().minusSeconds(2 * 60 * 60));
        final long lower = broadcast("likes-snap-lower", "LIVE", null);
        final long empty = broadcast("likes-snap-empty", "LIVE", null);
        redis.opsForValue().set(LikeService.key(live), "10");
        redis.opsForValue().set(LikeService.key(recent), "20");
        redis.opsForValue().set(LikeService.key(old), "30");
        redis.opsForValue().set(LikeService.key(lower), "5");
        snapshots.store(lower, 50);

        snapshots.snapshotAll();
        snapshots.snapshotAll();

        assertThat(storedTotal(live)).isEqualTo(10);
        assertThat(storedTotal(recent)).isEqualTo(20);
        assertThat(storedTotal(old)).isNull();
        assertThat(storedTotal(lower)).isEqualTo(50);
        assertThat(storedTotal(empty)).isNull();

        redis.opsForValue().set(LikeService.key(live), "11");
        snapshots.snapshotAll();
        assertThat(storedTotal(live)).isEqualTo(11);
    }
}
