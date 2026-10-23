package com.shoppinglive.live.observability;

import com.shoppinglive.live.stream.BroadcastStreamRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class LiveObservabilityConfiguration {
    /**
     * 이 pod 에 열린 SSE 연결 수. 연결은 CPU 를 거의 쓰지 않아 CPU 지표로는 파드 확장 시점을 잡을 수 없고, 열린 연결은 새 파드로 옮겨 가지 않아 연결
     * 수 자체가 확장 근거가 된다. 스크레이프 때 레지스트리를 직접 세므로 별도 집계 작업이 없다.
     */
    @Bean
    public Gauge sseConnections(MeterRegistry meters, BroadcastStreamRegistry streams) {
        return Gauge.builder(
                        "shoppinglive.live.sse.connections",
                        streams,
                        BroadcastStreamRegistry::connectionCount)
                .description("이 pod 에 열린 SSE 연결 수")
                .register(meters);
    }
}
