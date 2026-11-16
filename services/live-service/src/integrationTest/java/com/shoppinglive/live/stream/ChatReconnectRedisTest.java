package com.shoppinglive.live.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.client.member.MemberProfileClient;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 연결이 끊긴 동안 쓰인 채팅을 재연결한 시청자가 받는지 확인한다. 브라우저 EventSource 는 재연결 때 마지막으로 받은 id 를 Last-Event-ID 로 보낸다.
 * 기동: docker run -d --name live-redis-test -p 56379:6379 redis:7-alpine 실행: LIVE_REDIS_TEST=1
 * ./gradlew :services:live-service:redisTest --tests '*ChatReconnectRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("redis")
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("재연결 시 놓친 채팅 (실제 Redis)")
class ChatReconnectRedisTest extends LiveSecuritySupport {
    private static final int BEFORE_DISCONNECT = 10;
    private static final int WHILE_DISCONNECTED = 100;

    @LocalServerPort int port;
    @Autowired BroadcastService broadcasts;
    @Autowired StreamRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean MemberProfileClient members;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry properties) {
        properties.add(
                "spring.data.redis.port",
                () -> System.getenv().getOrDefault("LIVE_REDIS_PORT", "56379"));
        properties.add("live.stream.subscribe-retry", () -> "200ms");
        // 전달 경로를 검증하므로 화면 예산으로 생략되지 않게 한다. 예산 동작은 ChatRelayBudgetRedisTest 가 검증한다.
        properties.add("live.stream.chat-budget-per-second", () -> "1000000");
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

    @DisplayName("Last-Event-ID 로 재연결하면 끊긴 동안 쓰인 채팅을 모두 받는다")
    @Test
    void reconnectWithLastEventIdReceivesMissedChats() throws Exception {
        // PostgreSQL 로 돌리면 데이터가 남는다. 채널당 LIVE 하나 제약에 걸리지 않게 실행마다 채널을 새로 만든다.
        final long id = liveBroadcast("reconnect-" + UUID.randomUUID());

        final long lastSeen;
        try (SseTestClient first = new SseTestClient(port, id)) {
            ready(first);
            for (int n = 0; n < BEFORE_DISCONNECT; n++) {
                write(id, "끊기기 전-" + n);
            }
            lastSeen = receiveChatIds(first, BEFORE_DISCONNECT).getLast();
        }

        final Set<Long> missed = new HashSet<>();
        for (int n = 0; n < WHILE_DISCONNECTED; n++) {
            missed.add(write(id, "끊긴 동안-" + n));
        }

        try (SseTestClient second = new SseTestClient(port, id, String.valueOf(lastSeen))) {
            ready(second);
            // 재연결한 클라이언트가 지금 하는 복구: 최근 채팅 조회.
            final Set<Long> byHistory = recentChatIds(id);
            byHistory.retainAll(missed);

            // 재연결 뒤 새 채팅 하나를 끝 표시로 보내고, 그때까지 스트림으로 받은 채팅을 모은다.
            final long marker = write(id, "재연결 뒤");
            final Set<Long> byStream = new HashSet<>(receiveChatIdsUntil(second, marker));
            byStream.retainAll(missed);

            final Set<Long> unrecovered = new HashSet<>(missed);
            unrecovered.removeAll(byStream);
            unrecovered.removeAll(byHistory);
            System.out.printf(
                    "[ChatReconnect] 누락 건수=%d 스트림 복구 건수=%d 이력 복구 건수=%d 미복구 건수=%d%n",
                    missed.size(), byStream.size(), byHistory.size(), unrecovered.size());

            assertThat(byStream).as("재연결 스트림으로 다시 받은 놓친 채팅").hasSize(WHILE_DISCONNECTED);
        }
    }

    private static void ready(final SseTestClient stream) throws Exception {
        assertThat(stream.nextNonEmpty()).isEqualTo("event:stream.ready");
        assertThat(stream.nextNonEmpty()).startsWith("data:");
    }

    /** chat.created 의 id 를 도착 순서대로 count 개 모은다. */
    private static List<Long> receiveChatIds(final SseTestClient stream, final int count)
            throws Exception {
        final List<Long> ids = new ArrayList<>();
        String line = stream.nextNonEmpty();
        while (line != null && ids.size() < count) {
            if ("event:chat.created".equals(line)) {
                ids.add(chatId(stream));
            }
            if (ids.size() < count) {
                line = stream.nextNonEmpty();
            }
        }
        assertThat(ids).as("끊기기 전 채팅 수신").hasSize(count);
        return ids;
    }

    /** marker 채팅이 올 때까지 받은 chat.created id 를 모은다. */
    private static List<Long> receiveChatIdsUntil(final SseTestClient stream, final long marker)
            throws Exception {
        final List<Long> ids = new ArrayList<>();
        String line = stream.nextNonEmpty();
        while (line != null) {
            if ("event:chat.created".equals(line)) {
                final long messageId = chatId(stream);
                ids.add(messageId);
                if (messageId == marker) {
                    return ids;
                }
            }
            line = stream.nextNonEmpty();
        }
        throw new AssertionError("재연결 뒤 쓴 채팅을 받지 못했습니다.");
    }

    private static long chatId(final SseTestClient stream) throws Exception {
        final String idLine = stream.nextNonEmpty();
        assertThat(idLine).startsWith("id:");
        return Long.parseLong(idLine.substring(3));
    }

    private Set<Long> recentChatIds(final long id) throws Exception {
        final HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/v1/broadcasts/"
                                                        + id
                                                        + "/chats"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        final Set<Long> ids = new HashSet<>();
        for (final JsonNode chat : json.readTree(response.body()).get("data")) {
            ids.add(chat.get("messageId").asLong());
        }
        return ids;
    }

    private long liveBroadcast(final String name) {
        final long id =
                broadcasts
                        .register(
                                UUID.randomUUID().toString(),
                                new BroadcastInput(
                                        name,
                                        Instant.parse("2026-10-03T11:00:00Z"),
                                        "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
                                        "https://example.com/" + name + ".m3u8"))
                        .getId();
        jdbc.update(
                "UPDATE broadcast SET status = 'LIVE', started_at = CURRENT_TIMESTAMP WHERE id = ?",
                id);
        return id;
    }

    /** 채팅을 쓰고 저장된 채팅 번호를 돌려준다. */
    private long write(final long id, final String content) throws Exception {
        final HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/v1/broadcasts/"
                                                        + id
                                                        + "/chats"))
                                .header("Authorization", userBearer())
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(
                                                        Map.of("content", content))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).get("data").get("messageId").asLong();
    }
}
