package com.shoppinglive.live.stream.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 모두 시작값이다. 부하 측정으로 정한 값이 아니다. */
@ConfigurationProperties("live.stream")
public record StreamProperties(
    @DefaultValue("15s") Duration heartbeat,
    @DefaultValue("30m") Duration emitterTimeout,
    @DefaultValue("256") int queueCapacity,
    @DefaultValue("8") int writerThreads) {
}
