package com.shoppinglive.live.viewer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.shoppinglive.live.security.LiveSecuritySupport;
import com.shoppinglive.live.viewer.dto.ViewerTotal;
import com.shoppinglive.live.viewer.service.ViewerPresenceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ViewerHttpTest extends LiveSecuritySupport {
    @LocalServerPort int port;
    @MockitoBean ViewerPresenceService viewers;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("비로그인 조회·갱신을 허용하고 UUID 바인딩과 잘못된 토큰을 검증한다")
    void publicPresenceRequiresValidIdentifiers() throws Exception {
        UUID id = UUID.randomUUID();
        var total = new ViewerTotal(42, 2, Instant.now(), 60);
        when(viewers.total(42)).thenReturn(total);
        when(viewers.heartbeat(42, id)).thenReturn(total);
        var base = "http://127.0.0.1:" + port + "/v1/broadcasts/42/viewers";
        assertThat(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(base)).GET().build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(200);
        var result =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/heartbeat"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"viewerId\":\"" + id + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("\"total\":2");
        for (String body : new String[] {"{}", "{\"viewerId\":\"bad\"}"}) {
            assertThat(
                            http.send(
                                            HttpRequest.newBuilder(URI.create(base + "/heartbeat"))
                                                    .header("Content-Type", "application/json")
                                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                                    .build(),
                                            HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(400);
        }
        assertThat(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(base))
                                                .header("Authorization", "Bearer invalid")
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(401);
    }
}
