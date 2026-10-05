package com.shoppinglive.live.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.application.BroadcastService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 servlet container 와 HTTP 로 SSE 연결·정리를 검증한다. MockMvc 는 실제 스트리밍을 재현하지 못한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("방송 SSE 연결 (실제 HTTP)")
class BroadcastStreamHttpTest extends LiveSecuritySupport {
    @LocalServerPort int port;
    @Autowired BroadcastService broadcasts;
    @Autowired BroadcastStreamRegistry registry;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private long broadcast(final String name, final String status) {
        final long id = broadcasts.register(UUID.randomUUID().toString(), new BroadcastInput(name,
            Instant.parse("2026-10-03T11:00:00Z"), "arn:aws:ivs:ap-northeast-2:1:channel/" + name,
            "https://example.com/" + name + ".m3u8")).getId();
        jdbc.update("UPDATE broadcast SET status = ?, started_at = CURRENT_TIMESTAMP WHERE id = ?", status, id);
        return id;
    }

    private HttpRequest events(final Object id) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/broadcasts/" + id + "/events"))
            .header("Accept", "text/event-stream").build();
    }

    @DisplayName("토큰 없이 LIVE 방송에 연결하면 stream.ready 를 받고 프록시 buffering 방지 헤더가 온다")
    @Test
    void anonymousConnectionReceivesReady() throws Exception {
        final long id = broadcast("stream-ready", "LIVE");
        accessSessions.fail();

        try (SseTestClient stream = new SseTestClient(port, id)) {
            assertThat(stream.response.statusCode()).isEqualTo(200);
            assertThat(stream.response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(stream.response.headers().firstValue("Cache-Control")).hasValue("no-cache");
            assertThat(stream.response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
            assertThat(stream.nextNonEmpty()).isEqualTo("event:stream.ready");
            assertThat(stream.nextNonEmpty()).isEqualTo("data:{\"broadcastId\":" + id + "}");
            assertThat(registry.connectionCount(id)).isEqualTo(1);
        }
    }

    @DisplayName("LIVE 가 아니면 409, 없으면 404, id 가 숫자가 아니면 400 을 JSON 오류로 반환하고 등록을 남기지 않는다")
    @Test
    void nonLiveBroadcastIsRejectedBeforeTheStreamStarts() throws Exception {
        for (final String status : new String[] {"PREPARING", "ENDED"}) {
            final long id = broadcast("stream-" + status.toLowerCase(), status);
            final HttpResponse<String> response = http.send(events(id), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
            assertThat(response.body()).contains("\"code\":\"CONFLICT\"");
            assertThat(registry.connectionCount(id)).isZero();
        }
        final HttpResponse<String> missing = http.send(events(Long.MAX_VALUE), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"code\":\"NOT_FOUND\"");
        final HttpResponse<String> invalid = http.send(events("abc"), HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"code\":\"INVALID_REQUEST\"");
    }

    @DisplayName("해당 방송의 이벤트와 heartbeat 만 실제 연결로 전달된다")
    @Test
    void deliversOwnBroadcastEventsAndHeartbeat() throws Exception {
        final long mine = broadcast("stream-mine", "LIVE");
        final long other = broadcast("stream-other", "LIVE");

        try (SseTestClient stream = new SseTestClient(port, mine)) {
            stream.nextNonEmpty();
            stream.nextNonEmpty();

            registry.publish(other, StreamEvent.of("likes.updated", Map.of("total", 1)));
            registry.publish(mine, new StreamEvent("chat.created", "9001", Map.of("messageId", 9001)));
            registry.heartbeat();

            assertThat(stream.nextNonEmpty()).isEqualTo("event:chat.created");
            assertThat(stream.nextNonEmpty()).isEqualTo("id:9001");
            assertThat(stream.nextNonEmpty()).isEqualTo("data:{\"messageId\":9001}");
            assertThat(stream.nextNonEmpty()).isEqualTo(":heartbeat");
        }
    }

    @DisplayName("클라이언트가 끊으면 다음 전송 시도에서 등록이 지워진다")
    @Test
    void disconnectedClientIsRemoved() throws Exception {
        final long id = broadcast("stream-disconnect", "LIVE");
        final SseTestClient stream = new SseTestClient(port, id);
        stream.nextNonEmpty();
        stream.close();

        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (registry.connectionCount(id) > 0 && System.nanoTime() < deadline) {
            registry.heartbeat();
            Thread.sleep(50);
        }
        assertThat(registry.connectionCount(id)).isZero();
    }

    @DisplayName("마지막 이벤트 뒤 서버가 연결을 닫으면 클라이언트 스트림이 끝난다")
    @Test
    void serverCloseEndsTheClientStream() throws Exception {
        final long id = broadcast("stream-close", "LIVE");

        try (SseTestClient stream = new SseTestClient(port, id)) {
            stream.nextNonEmpty();
            stream.nextNonEmpty();
            registry.publishAndClose(id, StreamEvent.of("broadcast.ended", Map.of("broadcastId", id)));

            assertThat(stream.nextNonEmpty()).isEqualTo("event:broadcast.ended");
            assertThat(stream.nextNonEmpty()).startsWith("data:");
            assertThat(stream.nextNonEmpty()).isEqualTo(SseTestClient.END_OF_STREAM);
            assertThat(registry.connectionCount(id)).isZero();
        }
    }

    @DisplayName("Redis 에 연결할 수 없어도 앱은 기동하고 구독은 재시도 상태로 남는다")
    @Test
    void startsWithoutRedis(@Autowired final StreamRelay relay) {
        // 테스트 설정의 Redis 포트는 닫혀 있다. 여기까지 온 것이 기동 성공이다.
        relay.subscribe();

        assertThat(relay.subscribed()).isFalse();
    }
}
