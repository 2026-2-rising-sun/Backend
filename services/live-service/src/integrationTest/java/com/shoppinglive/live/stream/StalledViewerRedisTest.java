package com.shoppinglive.live.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willReturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.client.member.MemberProfileClient;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * 데이터를 읽지 않는 시청자 연결이 공용 writer 를 붙잡아 다른 시청자의 전달을 늦추는지 확인한다. 멈춘 시청자는 응답을 읽지 않는 TCP 연결로 흉내 낸다. OS 가
 * 수신 버퍼를 자동으로 키우므로(macOS 최대 4MB) 서버 쓰기가 막히기까지 필요한 채팅 수는 환경마다 다르다. 그래서 100건씩 보내며 매번 지연을 잰다. 기동:
 * docker run -d --name live-redis-test -p 56379:6379 redis:7-alpine 실행: LIVE_REDIS_TEST=1 ./gradlew
 * :services:live-service:redisTest --tests '*StalledViewerRedisTest'
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("redis")
@EnabledIfEnvironmentVariable(named = "LIVE_REDIS_TEST", matches = "1")
@DisplayName("멈춘 시청자와 다른 시청자의 전달 지연 (실제 Redis)")
class StalledViewerRedisTest extends LiveSecuritySupport {
    /** 예전 고정 writer 수(8)와 같다. 고정 풀이면 이만큼 멈춘 연결이 모든 전송을 막았다. */
    private static final int STALLED_VIEWERS = 8;

    /** 한 번에 보내는 양. 정상 시청자의 대기 한도(queue-capacity 256)를 넘지 않게 작게 둔다. */
    private static final int CHUNK = 100;

    /** 한 건이 약 0.75KB 라 6000건이면 멈춘 연결 하나에 약 4.5MB 로, macOS 수신 버퍼 최대치를 넘는다. */
    private static final int MAX_CHATS = 6_000;

    private static final int PROBES = 5;
    private static final long TARGET_P99_MILLIS = 1_500;

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

    @DisplayName("writer 수만큼 멈춘 시청자가 있어도 정상 시청자는 목표 지연 안에 채팅을 받는다")
    @Test
    void stalledViewersDoNotDelayOthers() throws Exception {
        // PostgreSQL 로 돌리면 데이터가 남는다. 채널당 LIVE 하나 제약에 걸리지 않게 실행마다 채널을 새로 만든다.
        final long id = liveBroadcast("stalled-" + UUID.randomUUID());

        try (SseTestClient viewer = new SseTestClient(port, id)) {
            assertThat(viewer.nextNonEmpty()).isEqualTo("event:stream.ready");
            assertThat(viewer.nextNonEmpty()).startsWith("data:");

            final List<Long> before = probe(id, viewer);

            final List<Socket> stalled = new ArrayList<>();
            try {
                for (int n = 0; n < STALLED_VIEWERS; n++) {
                    stalled.add(openStalledViewer(id));
                }
                // 연결이 등록될 시간을 준다.
                Thread.sleep(500);
                final String body = "가".repeat(200);
                long worst = 0;
                int sent = 0;
                while (sent < MAX_CHATS && worst < TARGET_P99_MILLIS) {
                    for (int n = 0; n < CHUNK; n++) {
                        write(id, body);
                    }
                    sent += CHUNK;
                    final long started = System.nanoTime();
                    awaitChat(viewer, write(id, "측정-" + sent));
                    worst = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    System.out.printf("[StalledViewer] sent=%d probe(ms)=%d%n", sent, worst);
                }
                // 막힌 뒤 이어지는 채팅도 늦는지 본다.
                final List<Long> after = probe(id, viewer);

                System.out.printf(
                        "[StalledViewer] before(ms)=%s blockedAt=%d after(ms)=%s%n",
                        before, sent, after);
                assertThat(worst)
                        .as("멈춘 시청자 %d명이 있을 때 정상 시청자 수신 지연(ms)", STALLED_VIEWERS)
                        .isLessThan(TARGET_P99_MILLIS);
            } finally {
                for (final Socket socket : stalled) {
                    socket.close();
                }
            }
        }
    }

    /** 채팅을 하나씩 쓰고, 정상 시청자가 그 채팅을 받기까지 걸린 시간을 잰다. */
    private List<Long> probe(final long id, final SseTestClient viewer) throws Exception {
        final List<Long> millis = new ArrayList<>();
        for (int n = 0; n < PROBES; n++) {
            final long started = System.nanoTime();
            final long messageId = write(id, "측정-" + n);
            awaitChat(viewer, messageId);
            millis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        return millis;
    }

    /** 앞서 쓴 채팅들도 함께 오므로 원하는 번호가 나올 때까지 버린다. 최대 120초 기다린다. */
    private static void awaitChat(final SseTestClient viewer, final long messageId)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            final String line = viewer.nextNonEmpty();
            if (line == null) {
                continue;
            }
            assertThat(line).as("정상 시청자 연결이 닫혔다").isNotEqualTo(SseTestClient.END_OF_STREAM);
            if (("id:" + messageId).equals(line)) {
                return;
            }
        }
        throw new AssertionError("120초 안에 채팅 " + messageId + " 을 받지 못했습니다.");
    }

    /** SSE 를 요청한 뒤 응답을 전혀 읽지 않는 연결. 수신 버퍼를 작게 잡아 서버 쪽 쓰기가 빨리 막히게 한다. */
    private Socket openStalledViewer(final long id) throws Exception {
        final Socket socket = new Socket();
        socket.setReceiveBufferSize(1024);
        socket.connect(new InetSocketAddress("127.0.0.1", port));
        final OutputStream out = socket.getOutputStream();
        out.write(
                ("GET /v1/broadcasts/"
                                + id
                                + "/events HTTP/1.1\r\n"
                                + "Host: 127.0.0.1:"
                                + port
                                + "\r\n"
                                + "Accept: text/event-stream\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return socket;
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
