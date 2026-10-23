package com.shoppinglive.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.common.web.WebConfiguration;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@SpringBootTest(
        classes = ObservabilityHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=observability",
            "spring.application.name=observability-test"
        })
@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
class ObservabilityHttpTest {
    static final int MANAGEMENT = availablePort();
    @LocalServerPort int port;

    static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void management(DynamicPropertyRegistry properties) {
        properties.add("management.server.port", () -> MANAGEMENT);
    }

    HttpResponse<String> get(int target, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + path))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("관리 포트만 Prometheus를 공개하고 실제 API 경로 템플릿을 계측한다")
    void isolatesMetricsAndMeasuresRoute(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        assertThat(get(port, "/items/123").statusCode()).isEqualTo(200);
        assertThat(output.getAll())
                .contains("\"event\":\"http_completed\"", "\"requestId\"")
                .doesNotContain("has already been written");
        var metrics = get(MANAGEMENT, "/actuator/prometheus");
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body())
                .contains(
                        "http_server_requests_seconds_bucket",
                        "uri=\"/items/{id}\"",
                        "application=\"observability-test\"");
        assertThat(get(port, "/actuator/prometheus").statusCode()).isIn(403, 404);
    }

    @Test
    @DisplayName("이벤트 MDC와 업무 식별자가 겹쳐도 JSON 로그가 정상 출력된다")
    void businessLogKeepsMdcWithoutDuplicate(
            org.springframework.boot.test.system.CapturedOutput output) {
        var telemetry =
                new BusinessTelemetry(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        try {
            org.slf4j.MDC.put("eventId", "event-log-test");
            telemetry.count(
                    "sample.business",
                    io.micrometer.core.instrument.Tags.of("result", "applied"),
                    java.util.Map.of("eventId", "event-log-test"));
            telemetry.failure(
                    "sample.failure",
                    io.micrometer.core.instrument.Tags.of("result", "failed"),
                    java.util.Map.of("eventId", "event-log-test"));
            telemetry.duration(
                    "sample.duration",
                    io.micrometer.core.instrument.Tags.of("result", "applied"),
                    java.util.Map.of("eventId", "event-log-test"),
                    System.nanoTime());
        } finally {
            org.slf4j.MDC.remove("eventId");
        }
        assertThat(output.getAll())
                .contains(
                        "\"event\":\"sample.business\"",
                        "\"event\":\"sample.failure\"",
                        "\"event\":\"sample.duration\"")
                .doesNotContain("has already been written");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({WebConfiguration.class, Controller.class})
    static class App {
        @Bean
        SecurityFilterChain appSecurity(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }

    @RestController
    static class Controller {
        @GetMapping("/items/{id}")
        String item(@PathVariable String id) {
            return "ok";
        }
    }
}
