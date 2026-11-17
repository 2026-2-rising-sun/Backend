package com.shoppinglive.payment.client.toss;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;

/** 운영과 같은 빈 생성 경로와 실제 JDK HttpClient 소켓으로 확인한다. MockRestServiceServer는 타임아웃과 실제 헤더를 만들지 못한다. */
class TossClientConfigurationTest {
    static final String SECRET = "test_sk_unit_secret";
    static final String DONE_RESPONSE_HEAD =
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n";
    static final String PARTIAL_BODY = "{\"paymentKey\":\"pk";

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
    @DisplayName("PAY-PG-01 비밀 키가 비어 있어도 기동하고, 토스에 요청을 보내지 않고 NotSent다")
    void blankSecretKeyStartsAndReturnsNotSentWithoutCalling() throws Exception {
        try (RawServer server = RawServer.respond("", Duration.ofSeconds(5))) {
            TossPaymentsClient client = clientFor(server, " ", Duration.ofMillis(300));

            assertThat(client.confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1"))
                    .isInstanceOf(TossResult.NotSent.class)
                    .extracting(TossResult::httpStatus)
                    .isNull();
            assertThat(client.getPayment("pk-1")).isInstanceOf(TossResult.NotSent.class);
            assertThat(client.cancel("pk-1", "사유", "cancel-pg-order-1"))
                    .isInstanceOf(TossResult.NotSent.class);
            assertThat(server.request()).isNotDone();
        }
    }

    @Test
    @DisplayName("PAY-PG-04 공통 RestClient 커스터마이저의 내부 요청 ID 헤더는 토스로 나가지 않는다")
    void doesNotInheritSharedRestClientCustomizers() throws Exception {
        String doneBody =
                "{\"paymentKey\":\"pk-1\",\"orderId\":\"pg-order-1\",\"status\":\"DONE\",\"totalAmount\":15000}";
        String response =
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + doneBody.length()
                        + "\r\n\r\n"
                        + doneBody;
        try (RawServer server = RawServer.respond(response, Duration.ofSeconds(2))) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
                    .withUserConfiguration(TossClientConfiguration.class)
                    // 공통 WebConfiguration의 요청 ID 전달 커스터마이저와 같은 역할이다.
                    .withBean(
                            RestClientCustomizer.class,
                            () -> builder -> builder.defaultHeader("X-Request-Id", "internal-id"))
                    .withPropertyValues(
                            "payment.toss.base-url=http://127.0.0.1:" + server.port(),
                            "payment.toss.secret-key=" + SECRET,
                            "payment.toss.read-timeout=5s")
                    .run(
                            context ->
                                    assertThat(
                                                    context.getBean(TossPaymentsClient.class)
                                                            .confirm(
                                                                    "pk-1",
                                                                    "pg-order-1",
                                                                    15000,
                                                                    "confirm-pg-order-1"))
                                            .isInstanceOf(TossResult.Success.class));

            String request = server.request().get(5, TimeUnit.SECONDS).toLowerCase();
            assertThat(request).contains("authorization: basic ").contains("idempotency-key: ");
            assertThat(request).doesNotContain("x-request-id");
        }
    }

    @Test
    @DisplayName("PAY-PG-01 연결 거부는 NotSent")
    void connectionRefusedIsNotSent() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        assertThat(
                        clientFor("http://127.0.0.1:" + closedPort, SECRET, Duration.ofSeconds(1))
                                .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1"))
                .isInstanceOf(TossResult.NotSent.class)
                .extracting(TossResult::httpStatus)
                .isNull();
    }

    @Test
    @DisplayName("PAY-PG-03 응답 헤더가 오지 않아 읽기 타임아웃이 나면 Unknown")
    void readTimeoutBeforeHeadersIsUnknown() throws Exception {
        try (RawServer server = RawServer.respond("", Duration.ofSeconds(5))) {
            TossResult result =
                    clientFor(server, SECRET, Duration.ofMillis(300))
                            .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");

            assertThat(result).isInstanceOf(TossResult.Unknown.class);
            assertThat(result.httpStatus()).isNull();
        }
    }

    @Test
    @DisplayName("PAY-PG-03·04 헤더 뒤 본문이 멈추면 읽기 타임아웃 안에 Unknown으로 끝낸다")
    void bodyStallAfterHeadersIsUnknownWithinReadTimeout() throws Exception {
        try (RawServer server =
                RawServer.respond(DONE_RESPONSE_HEAD + PARTIAL_BODY, Duration.ofSeconds(10))) {
            long startedAt = System.nanoTime();

            TossResult result =
                    clientFor(server, SECRET, Duration.ofMillis(300))
                            .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");

            assertThat(result).isInstanceOf(TossResult.Unknown.class);
            assertThat(result.httpStatus()).as("데드라인 초과는 응답 없음").isNull();
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                    .isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    @DisplayName("PAY-PG-03 응답 본문 중간에 연결이 끊기면 Unknown")
    void responseCutMidBodyIsUnknown() throws Exception {
        try (RawServer server =
                RawServer.respond(DONE_RESPONSE_HEAD + PARTIAL_BODY, Duration.ZERO)) {
            TossResult result =
                    clientFor(server, SECRET, Duration.ofSeconds(5))
                            .confirm("pk-1", "pg-order-1", 15000, "confirm-pg-order-1");

            assertThat(result).isInstanceOf(TossResult.Unknown.class);
            assertThat(result.httpStatus()).as("본문 끊김은 응답 없음").isNull();
        }
    }

    private static TossPaymentsClient clientFor(
            RawServer server, String secretKey, Duration readTimeout) {
        return clientFor("http://127.0.0.1:" + server.port(), secretKey, readTimeout);
    }

    /** 운영 빈과 같은 경로로 만든다. */
    private static TossPaymentsClient clientFor(
            String baseUrl, String secretKey, Duration readTimeout) {
        return new TossClientConfiguration()
                .tossPaymentsClient(
                        new TossProperties(baseUrl, secretKey, Duration.ofSeconds(1), readTimeout));
    }

    /** 연결 하나를 받아 요청 헤더를 기록하고 rawResponse를 쓴 뒤 holdOpen만큼 연결을 붙잡는다. 요청을 받지 않았으면 request는 끝나지 않는다. */
    private record RawServer(ServerSocket socket, CompletableFuture<String> request)
            implements AutoCloseable {

        static RawServer respond(String rawResponse, Duration holdOpen) throws IOException {
            RawServer server =
                    new RawServer(
                            new ServerSocket(0, 1, InetAddress.getLoopbackAddress()),
                            new CompletableFuture<>());
            Thread.ofVirtual().start(() -> server.handle(rawResponse, holdOpen));
            return server;
        }

        int port() {
            return socket.getLocalPort();
        }

        private void handle(String rawResponse, Duration holdOpen) {
            try {
                // 로컬 모니터링 등 다른 프로그램이 임시 포트로 붙을 수 있다. 토스 경로 요청만 받는다.
                while (true) {
                    try (Socket connection = socket.accept()) {
                        String head = readRequest(connection.getInputStream());
                        if (!head.startsWith("GET /v1/payments")
                                && !head.startsWith("POST /v1/payments")) {
                            continue;
                        }
                        request.complete(head);
                        connection
                                .getOutputStream()
                                .write(rawResponse.getBytes(StandardCharsets.UTF_8));
                        connection.getOutputStream().flush();
                        Thread.sleep(holdOpen);
                        return;
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // 테스트가 끝나 소켓이 닫히면 멈춘다.
            }
        }

        /** 본문까지 다 읽는다. 읽지 않은 데이터를 남기고 닫으면 연결이 리셋되어 응답을 잃을 수 있다. */
        private static String readRequest(InputStream input) throws IOException {
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            while (!headers.toString(StandardCharsets.ISO_8859_1).contains("\r\n\r\n")) {
                int next = input.read();
                if (next < 0) {
                    break;
                }
                headers.write(next);
            }
            String head = headers.toString(StandardCharsets.ISO_8859_1);
            if (head.toLowerCase().contains("transfer-encoding: chunked")) {
                // JDK HttpClient는 본문 길이를 모르면 chunked로 보낸다. 마지막 0 크기 청크까지 읽는다.
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                while (!body.toString(StandardCharsets.ISO_8859_1).endsWith("0\r\n\r\n")) {
                    int next = input.read();
                    if (next < 0) {
                        break;
                    }
                    body.write(next);
                }
            } else {
                input.readNBytes(contentLength(head));
            }
            return head;
        }

        private static int contentLength(String head) {
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    return Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            return 0;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
