package com.shoppinglive.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpAccessSessionVerifierTest {
    private static final String KEY = "test-member-session-service-credential-32-characters";
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsOnlyCallerCredentialAndExactIdentityOnEveryCallWithoutCaching() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/internal/auth/sessions/check", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("X-Service-Token")).isEqualTo(KEY);
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            var body = mapper.readTree(exchange.getRequestBody());
            assertThat(body.size()).isEqualTo(2);
            assertThat(body.path("memberId").asText()).isEqualTo(MEMBER.toString());
            assertThat(body.path("sessionId").asText()).isEqualTo(SESSION.toString());
            String response = envelope(calls.incrementAndGet() == 1);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var verifier = verifier(server, Duration.ofSeconds(1));
            assertThat(verifier.isActive(MEMBER, SESSION)).isTrue();
            assertThat(verifier.isActive(MEMBER, SESSION)).isFalse();
            assertThat(calls.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @Test
    void rejectsNon200RedirectsAndMalformedEnvelopesWithoutRetry() throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>(envelope(true));
        var calls = new AtomicInteger();
        var redirected = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/internal/auth/sessions/check", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", "/redirect");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> { redirected.incrementAndGet(); exchange.close(); });
        server.start();
        try {
            var verifier = verifier(server, Duration.ofSeconds(1));
            for (int code : List.of(201, 302, 401, 403, 404, 500, 503)) {
                status.set(code);
                assertUnavailable(verifier);
            }
            status.set(200);
            List<String> invalid = List.of("", "null", "[]", "{}", "not-json",
                "{\"success\":true,\"data\":{\"active\":\"true\"},\"error\":null}",
                "{\"success\":true,\"data\":{},\"error\":null}",
                "{\"success\":\"true\",\"data\":{\"active\":true},\"error\":null}",
                "{\"success\":false,\"data\":{\"active\":true},\"error\":null}",
                "{\"success\":true,\"data\":{\"active\":true}}",
                "{\"success\":true,\"data\":{\"active\":true},\"error\":{}}",
                envelope(true) + " {}",
                "{\"success\":true,\"data\":{\"active\":false,\"active\":true},\"error\":null}");
            for (String value : invalid) { body.set(value); assertUnavailable(verifier); }
            assertThat(calls.get()).isEqualTo(7 + invalid.size());
            assertThat(redirected.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    void actualReadTimeoutAndConnectionFailureAreUnavailableWithoutAllowFallback() throws Exception {
        var requestArrived = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/v1/internal/auth/sessions/check", exchange -> {
            requestArrived.countDown();
            try { releaseResponse.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var verifier = verifier(server, Duration.ofMillis(200));
        try {
            long start = System.nanoTime();
            assertUnavailable(verifier);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(requestArrived.getCount()).isZero();
            assertThat(elapsedMs).isBetween(150L, 2500L);
        } finally {
            releaseResponse.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
        assertUnavailable(verifier);
    }

    private HttpAccessSessionVerifier verifier(HttpServer server, Duration timeout) {
        return new HttpAccessSessionVerifier(new AccessSessionProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), KEY, timeout, timeout), mapper);
    }
    private void assertUnavailable(HttpAccessSessionVerifier verifier) {
        assertThatThrownBy(() -> verifier.isActive(MEMBER, SESSION)).isInstanceOf(AccessSessionUnavailableException.class);
    }
    private static String envelope(boolean active) {
        return "{\"success\":true,\"data\":{\"active\":" + active + "},\"error\":null}";
    }
}
