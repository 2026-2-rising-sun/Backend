package com.shoppinglive.live.broadcast.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.broadcast.dto.BroadcastInput;
import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.broadcast.repository.BroadcastProductRepository;
import com.shoppinglive.live.broadcast.repository.BroadcastRepository;
import com.shoppinglive.live.broadcast.service.BroadcastService;
import com.shoppinglive.live.security.LiveSecuritySupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 실제 서블릿 요청으로 JSON 바인딩, 경로 매칭, 운영용 보안 필터를 검증한다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "spring.datasource.url=jdbc:h2:mem:live_input_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        })
class LiveInputHttpTest extends LiveSecuritySupport {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired BroadcastService service;
    @Autowired BroadcastRepository repository;
    @Autowired BroadcastProductRepository links;
    private Broadcast broadcast;

    @BeforeEach
    void ownFixture() {
        broadcast =
                service.register(
                        "input-http-" + UUID.randomUUID(),
                        new BroadcastInput(
                                "original",
                                Instant.parse("2026-10-02T00:00:00Z"),
                                "arn:aws:ivs:channel/input",
                                "https://example.test/input.m3u8"));
    }

    @AfterEach
    void removeOwnFixture() {
        repository.deleteById(broadcast.getId());
    }

    @DisplayName("공개 경로의 ID 누락과 잘못된 형식은 400으로 응답한다")
    @ParameterizedTest
    @ValueSource(strings = {"/", "/?id=null", "/null", "/abc", "/%7Bid%7D"})
    void publicMissingOrMalformedIdIs400(String suffix) throws Exception {
        expect(request("GET", "/v1/broadcasts" + suffix, null, null), 400, "INVALID_REQUEST");
    }

    @DisplayName("목록·미존재 자원·보안 응답의 경계를 유지한다")
    @Test
    void collectionMissingResourceAndSecurityKeepTheirBoundaries() throws Exception {
        assertThat(request("GET", "/v1/broadcasts", null, null).statusCode()).isEqualTo(200);
        expect(request("GET", "/v1/broadcasts/999999999", null, null), 404, "NOT_FOUND");
        expect(request("GET", "/v1/broadcasts/", "Bearer invalid", null), 401, "UNAUTHORIZED");
        expect(request("POST", "/v1/broadcasts/", null, "{}"), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/seller/broadcasts", null, null), 401, "UNAUTHORIZED");
        expect(request("GET", "/v1/seller/broadcasts", userBearer(), null), 403, "FORBIDDEN");
    }

    @DisplayName("판매자 경로는 ID 누락 검증 전에 인증을 확인한다")
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH"})
    void sellerMustAuthenticateBeforeMissingIdValidation(String method) throws Exception {
        expect(
                request(method, "/v1/seller/broadcasts/", sellerBearer(), null),
                400,
                "INVALID_REQUEST");
        expect(request(method, "/v1/seller/broadcasts/", null, null), 401, "UNAUTHORIZED");
        expect(request(method, "/v1/seller/broadcasts/", userBearer(), null), 403, "FORBIDDEN");
    }

    @DisplayName("잘못된 PATCH 요청은 저장된 필드를 변경하지 않는다")
    @ParameterizedTest
    @ValueSource(
            strings = {
                "123",
                "[]",
                "true",
                "null",
                "{\"unexpectedField\":null}",
                "{\"unexpectedField\":{}}",
                "{\"title\":\"changed\",\"unexpectedField\":1}",
                "{\"title\":123}",
                "{\"title\":true}",
                "{\"title\":{}}",
                "{\"scheduledAt\":123}",
                "{\"scheduledAt\":123.5}",
                "{\"scheduledAt\":true}",
                "{\"channelArn\":123,\"playbackUrl\":\"https://example.test/a\"}",
                "{\"channelArn\":\"arn:test\",\"playbackUrl\":123}",
                "{\"title\":null}",
                "{\"scheduledAt\":null}"
            })
    void invalidPatchCannotChangeStoredFields(String body) throws Exception {
        expect(request("PATCH", patchPath(), sellerBearer(), body), 400, "INVALID_REQUEST");
        assertUnchanged();
    }

    @DisplayName("PATCH에서 생략한 필드는 유지하고 유효한 문자열은 수정한다")
    @Test
    void omittedPatchFieldsRemainUnchangedAndStringsStillUpdate() throws Exception {
        assertThat(request("PATCH", patchPath(), sellerBearer(), "{}").statusCode()).isEqualTo(200);
        assertUnchanged();
        var response = request("PATCH", patchPath(), sellerBearer(), "{\"title\":\"changed\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("data").path("title").asText())
                .isEqualTo("changed");
        assertThat(repository.findById(broadcast.getId()).orElseThrow().getVersion())
                .isGreaterThan(broadcast.getVersion());
    }

    @DisplayName("잘못된 연결 버전은 링크를 생성하지 않고 400으로 거절한다")
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"productId\":1,\"expectedVersion\":-1}",
                "{\"productId\":1,\"expectedVersion\":null}",
                "{\"productId\":1}"
            })
    void invalidLinkVersionIs400WithoutCreatingLink(String body) throws Exception {
        expect(
                request(
                        "POST",
                        "/v1/seller/broadcasts/" + broadcast.getId() + "/products",
                        sellerBearer(),
                        body),
                400,
                "INVALID_REQUEST");
        assertThat(links.findByBroadcastIdOrderByPositionAsc(broadcast.getId())).isEmpty();
        assertUnchanged();
    }

    @DisplayName("음수 버전은 변경이나 무변경 처리 전에 입력 오류로 거절한다")
    @Test
    void negativeVersionsAreInputErrorsBeforeMutationOrNoOp() throws Exception {
        String path = "/v1/seller/broadcasts/" + broadcast.getId();
        expect(
                request("PATCH", path + "?version=-1", sellerBearer(), "{}"),
                400,
                "INVALID_REQUEST");
        expect(
                request("POST", path + "/start?expectedVersion=-1", sellerBearer(), null),
                400,
                "INVALID_REQUEST");
        expect(
                request(
                        "DELETE",
                        path + "/products/99999999?expectedVersion=-1",
                        sellerBearer(),
                        null),
                400,
                "INVALID_REQUEST");
        expect(
                request(
                        "PUT",
                        path + "/products/order",
                        sellerBearer(),
                        "{\"linkIds\":[1],\"expectedVersion\":-1}"),
                400,
                "INVALID_REQUEST");
        assertThat(links.findByBroadcastIdOrderByPositionAsc(broadcast.getId())).isEmpty();
        assertUnchanged();
    }

    private String patchPath() {
        return "/v1/seller/broadcasts/" + broadcast.getId() + "?version=" + broadcast.getVersion();
    }

    private void assertUnchanged() {
        final Broadcast stored = repository.findById(broadcast.getId()).orElseThrow();
        assertThat(stored.getTitle()).isEqualTo(broadcast.getTitle());
        assertThat(stored.getScheduledAt()).isEqualTo(broadcast.getScheduledAt());
        assertThat(stored.getChannelArn()).isEqualTo(broadcast.getChannelArn());
        assertThat(stored.getPlaybackUrl()).isEqualTo(broadcast.getPlaybackUrl());
        assertThat(stored.getVersion()).isEqualTo(broadcast.getVersion());
    }

    private HttpResponse<String> request(String method, String path, String bearer, String body)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode expect(HttpResponse<String> response, int status, String code)
            throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        return body;
    }
}
