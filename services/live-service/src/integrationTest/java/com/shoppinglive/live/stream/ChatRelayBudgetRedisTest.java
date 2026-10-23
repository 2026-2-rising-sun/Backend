package com.shoppinglive.live.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.client.member.MemberProfileClient;
import com.shoppinglive.live.security.LiveSecuritySupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
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
 * 방송별 초당 채팅 전달 예산을 실제 Redis 로 검증한다. 기동: docker run -d --name live-redis-test -p 56379:6379
 * redis:7-alpine 실행: LIVE_REDIS_TEST=1 ./gradlew :services:live-service:redisTest --tests
 * '*ChatRelayBudgetRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("redis")
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("채팅 전달 예산 (실제 Redis)")
class ChatRelayBudgetRedisTest extends LiveSecuritySupport {
    private static final int BUDGET = 3;

    @LocalServerPort int port;
    @Autowired BroadcastService broadcasts;
    @Autowired StreamRelay relay;
    @Autowired ChatRelayBudget budget;
    @Autowired MeterRegistry meters;
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
        properties.add("live.stream.chat-budget-per-second", () -> String.valueOf(BUDGET));
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

    @DisplayName("같은 초 안에서는 예산만큼만 허용하고 넘은 수를 메트릭에 남기며, 다음 초와 다른 방송은 따로 센다")
    @Test
    void countsPerBroadcastPerSecond() {
        final long broadcastId = System.nanoTime();
        final Instant second = Instant.parse("2026-10-03T11:00:00.100Z");
        final double skippedBefore = skipped();

        for (int n = 0; n < BUDGET; n++) {
            assertThat(budget.tryAcquire(broadcastId, second.plusMillis(n * 100))).isTrue();
        }
        assertThat(budget.tryAcquire(broadcastId, second.plusMillis(800))).isFalse();
        assertThat(budget.tryAcquire(broadcastId, second.plusMillis(850))).isFalse();

        assertThat(skipped() - skippedBefore).isEqualTo(2.0);
        assertThat(budget.tryAcquire(broadcastId, second.plusSeconds(1))).isTrue();
        assertThat(budget.tryAcquire(broadcastId + 1, second)).isTrue();
    }

    @DisplayName("예산을 넘은 일반 채팅은 저장되지만 실시간으로는 일부만 전달된다")
    @Test
    void userChatsOverBudgetAreSavedButNotAllDelivered() throws Exception {
        final long id = liveBroadcast("budget-user-" + UUID.randomUUID());
        final int sent = BUDGET * 4;

        try (SseTestClient viewer = new SseTestClient(port, id)) {
            ready(viewer);
            for (int n = 0; n < sent; n++) {
                write(id, userBearer(), "일반-" + n);
            }
            final int delivered = countChats(viewer);

            assertThat(savedChats(id)).as("저장").isEqualTo(sent);
            // 보내는 동안 초가 바뀌면 다음 초 예산이 더해질 수 있다.
            assertThat(delivered).as("실시간 전달").isBetween(BUDGET, sent - 1);
        }
    }

    @DisplayName("판매자 채팅은 예산을 넘어도 모두 전달된다")
    @Test
    void sellerChatsBypassBudget() throws Exception {
        final long id = liveBroadcast("budget-seller-" + UUID.randomUUID());
        final int sent = BUDGET * 4;

        try (SseTestClient viewer = new SseTestClient(port, id)) {
            ready(viewer);
            for (int n = 0; n < sent; n++) {
                write(id, sellerBearer(), "판매자-" + n);
            }

            assertThat(countChats(viewer)).isEqualTo(sent);
        }
    }

    private double skipped() {
        return meters.counter("shoppinglive.live.chat.relay.skipped").count();
    }

    private static void ready(final SseTestClient stream) throws Exception {
        assertThat(stream.nextNonEmpty()).isEqualTo("event:stream.ready");
        assertThat(stream.nextNonEmpty()).startsWith("data:");
    }

    /** 5초 동안 더 오지 않을 때까지 받은 chat.created 수. */
    private static int countChats(final SseTestClient stream) throws Exception {
        int count = 0;
        String line = stream.nextNonEmpty();
        while (line != null) {
            if ("event:chat.created".equals(line)) {
                count++;
            }
            line = stream.nextNonEmpty();
        }
        return count;
    }

    private int savedChats(final long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM broadcast_chat WHERE broadcast_id = ?", Integer.class, id);
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

    private void write(final long id, final String bearer, final String content) throws Exception {
        final HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/v1/broadcasts/"
                                                        + id
                                                        + "/chats"))
                                .header("Authorization", bearer)
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(
                                                        Map.of("content", content))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
    }
}
