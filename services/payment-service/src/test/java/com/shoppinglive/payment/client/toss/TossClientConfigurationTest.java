package com.shoppinglive.payment.client.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;

/** 실제 JDK HttpClient 소켓 동작으로 예외 분류를 확인한다. MockRestServiceServer는 타임아웃을 만들지 못한다. */
class TossClientConfigurationTest {
    static final String SECRET = "test_sk_unit_secret";

    @Test
    @DisplayName("PAY-PG-01·04 기본 연결 타임아웃은 3초, 읽기 타임아웃은 60초다")
    void defaultTimeouts() {
        TossProperties properties =
                new Binder(
                                new MapConfigurationPropertySource(
                                        Map.of(
                                                "payment.toss.base-url",
                                                "https://api.tosspayments.com",
                                                "payment.toss.secret-key",
                                                SECRET)))
                        .bind("payment.toss", TossProperties.class)
                        .get();

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.toString()).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("PAY-PG-04 비밀 키가 비어 있으면 키 값 없이 기동을 막는다")
    void blankSecretKeyFailsFast() {
        TossProperties properties =
                new TossProperties(
                        "https://toss.test", " ", Duration.ofSeconds(3), Duration.ofSeconds(60));

        assertThatThrownBy(
                        () -> TossClientConfiguration.configure(RestClient.builder(), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_TOSS_SECRET_KEY");
    }

    @Test
    @DisplayName("PAY-PG-01 연결 거부는 NotSent")
    void connectionRefusedIsNotSent() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThat(
                        clientFor(closedPort, Duration.ofSeconds(1))
                                .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1"))
                .isInstanceOf(TossResult.NotSent.class);
    }

    @Test
    @DisplayName("PAY-PG-03 읽기 타임아웃은 Unknown")
    void readTimeoutIsUnknown() throws Exception {
        try (ServerSocket server = serve(null)) {
            TossResult result =
                    clientFor(server.getLocalPort(), Duration.ofMillis(300))
                            .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");

            assertThat(result).isInstanceOf(TossResult.Unknown.class);
        }
    }

    @Test
    @DisplayName("PAY-PG-03 응답 본문 중간에 연결이 끊기면 Unknown")
    void responseCutMidBodyIsUnknown() throws Exception {
        String partial =
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n{\"paymentKey\":\"pk";
        try (ServerSocket server = serve(partial)) {
            TossResult result =
                    clientFor(server.getLocalPort(), Duration.ofSeconds(5))
                            .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");

            assertThat(result).isInstanceOf(TossResult.Unknown.class);
        }
    }

    private static TossPaymentsClient clientFor(int port, Duration readTimeout) {
        TossProperties properties =
                new TossProperties(
                        "http://127.0.0.1:" + port, SECRET, Duration.ofSeconds(1), readTimeout);
        return new TossPaymentsClient(
                TossClientConfiguration.configure(RestClient.builder(), properties).build());
    }

    /** 연결 하나를 받아 요청을 읽고 rawResponse를 쓴 뒤 닫는다. null이면 응답 없이 연결만 붙잡는다. */
    private static ServerSocket serve(String rawResponse) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual()
                .start(
                        () -> {
                            try (Socket socket = server.accept()) {
                                socket.getInputStream().read(new byte[8192]);
                                if (rawResponse == null) {
                                    Thread.sleep(5_000);
                                } else {
                                    socket.getOutputStream()
                                            .write(rawResponse.getBytes(StandardCharsets.UTF_8));
                                    socket.getOutputStream().flush();
                                }
                            } catch (IOException | InterruptedException ignored) {
                                // 테스트가 끝나 소켓이 닫히면 멈춘다.
                            }
                        });
        return server;
    }
}
