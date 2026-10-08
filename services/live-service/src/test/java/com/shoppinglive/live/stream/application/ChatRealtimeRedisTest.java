package com.shoppinglive.live.stream.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.integration.member.MemberProfileClient;
import com.shoppinglive.live.integration.member.MemberProfileException;
import com.shoppinglive.live.integration.member.MemberProfileException.Reason;
import com.shoppinglive.live.security.LiveSecuritySupport;
import com.shoppinglive.live.like.application.LikeBroadcaster;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 실제 Redis 와 실제 HTTP 로 채팅 실시간 전달을 검증한다.
 * 기동: docker run -d --name live-redis-test -p 56379:6379 redis:7-alpine
 * 실행: LIVE_REDIS_TEST=1 ./gradlew :services:live-service:test --tests '*ChatRealtimeRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("채팅 실시간 전달 (실제 Redis)")
class ChatRealtimeRedisTest extends LiveSecuritySupport {
    @LocalServerPort int port;
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastStreamRegistry registry;
    @Autowired StreamRelay relay;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LikeBroadcaster likeBroadcaster;
    @MockitoBean MemberProfileClient members;

    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getenv().getOrDefault("LIVE_REDIS_PORT", "56379"));
        properties.add("live.stream.subscribe-retry", () -> "200ms");
    }

    @BeforeEach
    void waitForSubscription() throws Exception {
        willReturn("회원A").given(members).displayName(any(), any());
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!relay.subscribed() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(relay.subscribed()).isTrue();
    }

    private long liveBroadcast(final String name) {
        final long id = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name,
            Instant.parse("2026-10-03T11:00:00Z"), "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
            "https://example.com/" + name + ".m3u8")).getId();
        jdbc.update("UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        return id;
    }

    private HttpResponse<String> write(final long id, final String content) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/v1/broadcasts/" + id + "/chats"))
            .header("Authorization", userBearer()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of("content", content))))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void ready(final SseTestClient stream) throws Exception {
        assertThat(stream.nextNonEmpty()).isEqualTo("event:stream.ready");
        assertThat(stream.nextNonEmpty()).startsWith("data:");
    }

    /** 하나의 스트림에 합법적으로 섞이는 좋아요 프레임만 건너뛴다. 채팅은 건너뛰지 않는다. */
    private static String nextNonLikeLine(final SseTestClient stream) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            final String line = stream.nextNonEmpty();
            if (!"event:likes.updated".equals(line)) {
                return line;
            }
            assertThat(stream.nextNonEmpty()).startsWith("data:");
        } while (System.nanoTime() < deadline);
        throw new AssertionError("좋아요 외의 이벤트를 5초 안에 받지 못했습니다.");
    }

    private static String nextChatLine(final SseTestClient stream) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            final String line = nextNonLikeLine(stream);
            if (!":heartbeat".equals(line)) {
                return line;
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("채팅 이벤트를 5초 안에 받지 못했습니다.");
    }

    @DisplayName("저장된 채팅은 응답과 같은 내용으로 해당 방송 연결에만 chat.created 로 전달된다")
    @Test
    void savedChatReachesOnlyItsBroadcast() throws Exception {
        final long mine = liveBroadcast("realtime-mine");
        final long other = liveBroadcast("realtime-other");

        try (SseTestClient stream = new SseTestClient(port, mine);
             SseTestClient otherStream = new SseTestClient(port, other)) {
            ready(stream);
            ready(otherStream);
            // 정상적인 좋아요 이벤트가 채팅보다 먼저 와도, 채팅 내용과 방송 격리를 확인한다.
            jdbc.update("INSERT INTO broadcast_like_aggregate (broadcast_id, total, version) VALUES (?, 7, 7)", mine);
            jdbc.update("INSERT INTO broadcast_like_aggregate (broadcast_id, total, version) VALUES (?, 9, 9)", other);
            ReflectionTestUtils.invokeMethod(likeBroadcaster, "publishChanged");

            final HttpResponse<String> response = write(mine, "안녕하세요 😀");
            assertThat(response.statusCode()).isEqualTo(201);
            final JsonNode saved = json.readTree(response.body()).get("data");

            assertThat(nextChatLine(stream)).isEqualTo("event:chat.created");
            assertThat(stream.nextNonEmpty()).isEqualTo("id:" + saved.get("messageId").asLong());
            final String data = stream.nextNonEmpty();
            assertThat(data).startsWith("data:");
            assertThat(json.readTree(data.substring(5))).isEqualTo(saved);

            // 다른 방송에서는 좋아요 외에 채팅 없이 heartbeat를 받아야 한다.
            registry.heartbeat();
            assertThat(nextNonLikeLine(otherStream)).isEqualTo(":heartbeat");
        }
    }

    @DisplayName("저장에 실패한 채팅은 전달되지 않는다")
    @Test
    void failedWriteSendsNoEvent() throws Exception {
        final long id = liveBroadcast("realtime-failed");
        willThrow(new MemberProfileException(Reason.UNAVAILABLE)).given(members).displayName(any(), any());

        try (SseTestClient stream = new SseTestClient(port, id)) {
            ready(stream);

            assertThat(write(id, "전달되면 안 됨").statusCode()).isEqualTo(503);
            assertThat(write(id, " ").statusCode()).isEqualTo(400);

            registry.heartbeat();
            assertThat(nextNonLikeLine(stream)).isEqualTo(":heartbeat");
        }
    }

    @DisplayName("다른 인스턴스가 Redis 에 발행한 이벤트도 이 인스턴스의 연결에 전달된다")
    @Test
    void eventPublishedByAnotherInstanceIsDelivered() throws Exception {
        final long id = liveBroadcast("realtime-remote");

        try (SseTestClient stream = new SseTestClient(port, id)) {
            ready(stream);

            redis.convertAndSend(StreamRelay.CHANNEL, "{\"type\":\"chat.created\",\"broadcastId\":" + id
                + ",\"id\":\"77\",\"data\":{\"messageId\":77,\"content\":\"다른 pod\"}}");
            redis.convertAndSend(StreamRelay.CHANNEL, "깨진 메시지");

            assertThat(nextChatLine(stream)).isEqualTo("event:chat.created");
            assertThat(stream.nextNonEmpty()).isEqualTo("id:77");
            assertThat(stream.nextNonEmpty()).isEqualTo("data:{\"messageId\":77,\"content\":\"다른 pod\"}");
            // 깨진 메시지는 무시하고 연결과 구독은 유지된다.
            registry.heartbeat();
            assertThat(nextNonLikeLine(stream)).isEqualTo(":heartbeat");
            assertThat(relay.subscribed()).isTrue();
        }
    }
}
