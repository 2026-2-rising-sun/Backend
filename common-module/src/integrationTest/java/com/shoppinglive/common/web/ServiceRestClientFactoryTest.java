package com.shoppinglive.common.web;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class ServiceRestClientFactoryTest {
    @DisplayName("서비스 토큰과 요청 ID를 전달하고 원본 RestClient 빌더를 변경하지 않는다")
    @Test
    void forwardsServiceTokenAndRequestIdWithoutMutatingOriginalBuilder() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var token = new AtomicReference<String>();
        var requestId = new AtomicReference<String>();
        server.createContext(
                "/",
                exchange -> {
                    token.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
                    requestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var builder = RestClient.builder().baseUrl(url);
            new WebConfiguration().correlationIdRestClientCustomizer().customize(builder);
            var service =
                    ServiceRestClientFactory.create(
                            builder,
                            url,
                            "caller-token",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2));
            MDC.put(CorrelationId.MDC_KEY, "request-42");
            service.get().retrieve().toBodilessEntity();
            assertThat(token).hasValue("caller-token");
            assertThat(requestId).hasValue("request-42");
            builder.build().get().retrieve().toBodilessEntity();
            assertThat(token.get()).isNull();
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
            server.stop(0);
        }
    }

    @DisplayName("응답이 지연되면 설정한 읽기 제한 시간에 실패한다")
    @Test
    void failsDelayedResponseAtConfiguredReadTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var arrived = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext(
                "/",
                exchange -> {
                    arrived.countDown();
                    try {
                        release.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    ServiceRestClientFactory.create(
                            RestClient.builder(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "caller-token",
                            Duration.ofSeconds(1),
                            Duration.ofMillis(100));
            assertThatThrownBy(() -> client.get().retrieve().toBodilessEntity())
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(arrived.getCount()).isZero();
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
