package com.shoppinglive.live.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 동시에 쓰인 채팅을 모든 시청자가 같은 순서로 받는지 확인한다. 저장 커밋과 Redis 발행이 따로 일어나므로 도착 순서는 채팅 번호 순서와 다를 수 있고, 이는 허용한다.
 * Redis Pub/Sub 은 발행 순서대로 전달하므로 시청자끼리는 같은 순서가 된다. 번호보다 늦게 도착한 건수는 기록용으로 출력한다. 기동: docker run -d
 * --name live-redis-test -p 56379:6379 redis:7-alpine 실행: LIVE_REDIS_TEST=1 ./gradlew
 * :services:live-service:redisTest --tests '*ChatOrderingRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("redis")
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("채팅 도착 순서 (실제 Redis)")
class ChatOrderingRedisTest extends LiveSecuritySupport {
    private static final int WRITERS = 20;
    private static final int CHATS_PER_WRITER = 50;
    private static final int TOTAL = WRITERS * CHATS_PER_WRITER;

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

    @DisplayName("여러 명이 동시에 채팅을 보내도 모든 시청자는 같은 순서로 받는다")
    @Test
    void concurrentChatsArriveInSameOrderForEveryViewer() throws Exception {
        // PostgreSQL 로 돌리면 데이터가 남는다. 채널당 LIVE 하나 제약에 걸리지 않게 실행마다 채널을 새로 만든다.
        final long id = liveBroadcast("ordering-" + UUID.randomUUID());

        try (SseTestClient first = new SseTestClient(port, id);
                SseTestClient second = new SseTestClient(port, id)) {
            ready(first);
            ready(second);

            writeConcurrently(id);
            final List<Long> firstArrived = receiveChatIds(first);
            final List<Long> secondArrived = receiveChatIds(second);

            // 이미 더 큰 번호를 받은 뒤에 도착한 채팅 수. 새로고침(번호순 조회)하면 위치가 바뀌어 보이는 건수다.
            int late = 0;
            long highest = 0;
            for (final long messageId : firstArrived) {
                if (messageId < highest) {
                    late++;
                }
                highest = Math.max(highest, messageId);
            }
            System.out.printf(
                    "[ChatOrdering] sent=%d received=%d late=%d%n",
                    TOTAL, firstArrived.size(), late);

            assertThat(firstArrived).as("유실 없이 모두 도착").hasSize(TOTAL);
            assertThat(secondArrived).as("두 시청자의 도착 순서").isEqualTo(firstArrived);
        }
    }

    private static void ready(final SseTestClient stream) throws Exception {
        assertThat(stream.nextNonEmpty()).isEqualTo("event:stream.ready");
        assertThat(stream.nextNonEmpty()).startsWith("data:");
    }

    /** 모든 작성자가 동시에 출발하도록 묶는다. 실패 응답이 있으면 재현 조건이 깨진 것이므로 바로 실패시킨다. */
    private void writeConcurrently(final long id) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS)) {
            final List<Future<?>> writers = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                final int writer = w;
                writers.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (int n = 0; n < CHATS_PER_WRITER; n++) {
                                        final HttpResponse<String> response =
                                                write(id, "작성자" + writer + "-" + n);
                                        assertThat(response.statusCode()).isEqualTo(201);
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (final Future<?> writer : writers) {
                writer.get(60, TimeUnit.SECONDS);
            }
        }
    }

    /** 도착한 순서대로 chat.created 의 id 를 모은다. 5초 동안 아무것도 오지 않으면 끝낸다. */
    private static List<Long> receiveChatIds(final SseTestClient stream) throws Exception {
        final List<Long> ids = new ArrayList<>();
        String line = stream.nextNonEmpty();
        while (line != null && ids.size() < TOTAL) {
            if ("event:chat.created".equals(line)) {
                final String idLine = stream.nextNonEmpty();
                assertThat(idLine).startsWith("id:");
                ids.add(Long.parseLong(idLine.substring(3)));
            }
            line = stream.nextNonEmpty();
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

    private HttpResponse<String> write(final long id, final String content) throws Exception {
        return http.send(
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
                                        json.writeValueAsString(Map.of("content", content))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
