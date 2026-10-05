package com.shoppinglive.live.integration.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.live.integration.member.MemberProfileException.Reason;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 실제 HTTP 로 Member 본인 조회 계약과 오류 구분을 검증한다. */
@DisplayName("Member 표시 이름 client (실제 HTTP 서버 기반)")
class MemberProfileClientTest {
    private static final String MEMBER = "00000000-0000-4000-8000-000000000001";

    private HttpServer server;
    private MemberProfileClient client;
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        client = new HttpMemberProfileClient(MemberClientConfiguration.http(RestClient.builder(),
            "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(final int status, final String location, final String body) {
        server.createContext("/v1/members/me", exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String profile(final String memberId, final String displayName) {
        return "{\"success\":true,\"data\":{\"memberId\":\"" + memberId + "\",\"email\":\"a@example.com\","
            + "\"displayName\":" + displayName + ",\"roles\":[\"USER\"]},\"error\":null}";
    }

    @DisplayName("호출자의 토큰을 그대로 전달하고 표시 이름만 돌려준다")
    @Test
    void forwardsCallerTokenAndReturnsDisplayName() {
        respond(200, null, profile(MEMBER, "\"회원A\""));

        assertThat(client.displayName("Bearer caller-token", MEMBER)).isEqualTo("회원A");
        assertThat(receivedAuthorization.get()).isEqualTo("Bearer caller-token");
    }

    @DisplayName("이름 길이는 code point 로 세어 이모지 80자를 허용한다")
    @Test
    void displayNameLengthIsCountedInCodePoints() {
        respond(200, null, profile(MEMBER, "\"" + "😀".repeat(80) + "\""));

        assertThat(client.displayName("Bearer caller-token", MEMBER)).isEqualTo("😀".repeat(80));
    }

    @DisplayName("Member 의 401 은 UNAUTHORIZED 다")
    @Test
    void memberRejectionIsUnauthorized() {
        respond(401, null, "{}");

        assertReason(Reason.UNAUTHORIZED);
    }

    @DisplayName("장애·다른 회원의 응답·이름 없는 응답·redirect 는 UNAVAILABLE 이다")
    @Test
    void untrustedResponsesAreUnavailable() {
        respond(503, null, "{}");
        assertReason(Reason.UNAVAILABLE);

        server.removeContext("/v1/members/me");
        respond(200, null, profile("00000000-0000-4000-8000-000000000002", "\"다른회원\""));
        assertReason(Reason.UNAVAILABLE);

        server.removeContext("/v1/members/me");
        respond(200, null, profile(MEMBER, "null"));
        assertReason(Reason.UNAVAILABLE);

        server.removeContext("/v1/members/me");
        respond(200, null, profile(MEMBER, "\"" + "가".repeat(81) + "\""));
        assertReason(Reason.UNAVAILABLE);

        server.removeContext("/v1/members/me");
        respond(302, "http://127.0.0.1:" + server.getAddress().getPort() + "/elsewhere", "{}");
        assertReason(Reason.UNAVAILABLE);

        server.stop(0);
        assertReason(Reason.UNAVAILABLE);
    }

    private void assertReason(final Reason reason) {
        assertThatThrownBy(() -> client.displayName("Bearer caller-token", MEMBER))
            .isInstanceOfSatisfying(MemberProfileException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
