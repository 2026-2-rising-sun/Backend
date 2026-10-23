package com.shoppinglive.live.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.live.observability.LiveObservabilityConfiguration;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("SSE 연결 수 지표")
class StreamConnectionGaugeTest {
    private final BroadcastStreamRegistry streams =
            new BroadcastStreamRegistry(
                    new StreamProperties(Duration.ofSeconds(15), Duration.ofMinutes(30), 2, 1));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Gauge gauge =
            new LiveObservabilityConfiguration().sseConnections(meters, streams);

    @AfterEach
    void stop() {
        streams.shutdown();
    }

    @Test
    @DisplayName("방송이 달라도 이 pod 의 연결을 모두 더해 노출한다")
    void countsConnectionsAcrossBroadcasts() {
        streams.register(1L, new SseEmitter());
        streams.register(1L, new SseEmitter());
        streams.register(2L, new SseEmitter());

        assertThat(gauge.value()).isEqualTo(3.0);
        assertThat(meters.get("shoppinglive.live.sse.connections").gauge().value()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("연결이 닫히면 지표도 줄어든다")
    void decreasesWhenConnectionCloses() {
        final BroadcastConnection first = streams.register(1L, new SseEmitter());
        streams.register(1L, new SseEmitter());

        first.close();

        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("연결이 없으면 0 이다")
    void zeroWithoutConnections() {
        assertThat(gauge.value()).isZero();
    }
}
