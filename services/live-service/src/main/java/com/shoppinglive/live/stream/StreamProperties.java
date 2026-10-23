package com.shoppinglive.live.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 모두 시작값이다. 부하 측정으로 정한 값이 아니다. chatBudgetPerSecond 는 방송 하나에서 초당 실시간으로 전달할 일반 채팅 수다(GetStream 의 채널당
 * 초당 5건 throttle 기준).
 */
@ConfigurationProperties("live.stream")
public record StreamProperties(
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue("30m") Duration emitterTimeout,
        @DefaultValue("256") int queueCapacity,
        @DefaultValue("5") int chatBudgetPerSecond) {}
