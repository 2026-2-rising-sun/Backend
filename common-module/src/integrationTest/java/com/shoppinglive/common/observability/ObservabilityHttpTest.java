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
