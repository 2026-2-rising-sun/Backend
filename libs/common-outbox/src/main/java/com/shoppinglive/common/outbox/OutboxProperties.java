package com.shoppinglive.common.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param batchSize   한 주기에 읽는 최대 행 수
 * @param sendTimeout 한 건 전송을 기다리는 최대 시간. 넘으면 실패로 보고 다음 주기에 재시도한다
 */
@ConfigurationProperties("shoppinglive.outbox.relay")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration sendTimeout) {
}
