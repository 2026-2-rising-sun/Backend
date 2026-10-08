package com.shoppinglive.live.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PostgreSQL/H2의 좋아요 합계 SSE와 실제 Redis 방송 종료 relay를 HTTP로 검증한다.
 * 실행: LIVE_REDIS_TEST=1 ./gradlew :services:live-service:test --tests '*LikeRealtimeRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("좋아요 취소 SSE와 방송 종료 (실제 Redis relay)")
class LikeRealtimeRedisTest extends LiveSecuritySupport {
    @LocalServerPort int port;
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastStreamRegistry registry;
    @Autowired StreamRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getenv().getOrDefault("LIVE_REDIS_PORT", "56379"));
        properties.add("live.stream.subscribe-retry", () -> "200ms");
        properties.add("live.likes.publish-interval", () -> "100ms");
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

    private HttpResponse<String> set(long id, boolean liked) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
            + "/v1/broadcasts/" + id + "/likes/mine"))
            .header("Authorization", userBearer()).header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .PUT(HttpRequest.BodyPublishers.ofString("{\"liked\":" + liked + "}")).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode nextVersion(SseTestClient stream, long version) throws Exception {
        JsonNode data = nextData(stream, "likes.updated");
        while (data.get("version").asLong() < version) data = nextData(stream, "likes.updated");
        return data;
    }

    @Test void cancellationPublishesLowerTotalWithHigherVersion() throws Exception {
        final long id = broadcast("likes-realtime", "LIVE", null);
        try (SseTestClient stream = new SseTestClient(port, id)) {
            ready(stream);
            assertThat(set(id, true).statusCode()).isEqualTo(200);
            assertThat(nextVersion(stream, 1).get("total").asLong()).isEqualTo(1);
            assertThat(set(id, true).statusCode()).isEqualTo(200);
            assertThat(set(id, false).statusCode()).isEqualTo(200);
            final JsonNode cancelled = nextVersion(stream, 2);
            assertThat(cancelled.get("total").asLong()).isZero();
            assertThat(cancelled.get("broadcastId").asLong()).isEqualTo(id);
            Thread.sleep(500);
            registry.heartbeat();
            assertThat(stream.nextNonEmpty()).isEqualTo(":heartbeat");
        }
    }

    @Test void endKeepsCommittedTotalAndClosesConnections() throws Exception {
        final long id = broadcast("likes-end", "LIVE", null);
        try (SseTestClient stream = new SseTestClient(port, id)) {
            ready(stream);
            assertThat(set(id, true).statusCode()).isEqualTo(200);
            assertThat(post("/v1/admin/broadcasts/" + id + "/end", adminBearer()).statusCode()).isEqualTo(200);
            assertThat(nextData(stream, "broadcast.ended").get("broadcastId").asLong()).isEqualTo(id);
            assertThat(stream.nextNonEmpty()).isEqualTo(SseTestClient.END_OF_STREAM);
        }
        assertThat(registry.connectionCount(id)).isZero();
        assertThat(likesTotal(id)).isEqualTo(1);
        assertThat(set(id, false).statusCode()).isEqualTo(409);
        try (SseTestClient late = new SseTestClient(port, id)) {
            assertThat(late.response.statusCode()).isEqualTo(409);
        }
    }
}
