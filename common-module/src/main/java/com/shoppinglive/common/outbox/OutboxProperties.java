package com.shoppinglive.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param excludedTopics 별도 CDC가 전달하므로 일반·claim relay에서 배제할 토픽. 기본은 빈 목록이다.
 * @param batchSize 한 주기에 읽는 최대 행 수
 * @param sendTimeout 한 건 전송을 기다리는 최대 시간. 넘으면 실패로 보고 다음 주기에 재시도한다
 */
@ConfigurationProperties("shoppinglive.outbox.relay")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue java.util.List<String> excludedTopics) {
    public OutboxProperties(int batchSize, Duration sendTimeout) {
        this(batchSize, sendTimeout, java.util.List.of());
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public OutboxProperties {
        excludedTopics =
                excludedTopics == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(excludedTopics);
    }
}
