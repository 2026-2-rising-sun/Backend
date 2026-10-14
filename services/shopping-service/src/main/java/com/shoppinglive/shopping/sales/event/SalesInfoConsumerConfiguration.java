package com.shoppinglive.shopping.sales.event;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 판매정보 listener 의 재시도 정책.
 *
 * <p>기본 error handler 는 10회 뒤 포기하고 넘어가므로, DB 가 그보다 오래 죽으면 이벤트가 유실된다. 그래서 실패 레코드는 offset 을 넘기지 않고
 * 간격을 두고 무한히 다시 받는다. 재시도하는 동안 같은 partition 의 뒤 이벤트는 처리되지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class SalesInfoConsumerConfiguration {

    @Bean
    org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer
            salesInfoConsumerDefaults() {
        return factory ->
                factory.updateConfigs(
                        Map.of(
                                org.apache.kafka.clients.consumer.ConsumerConfig
                                        .ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                org.apache.kafka.clients.consumer.ConsumerConfig
                                        .AUTO_OFFSET_RESET_CONFIG,
                                "earliest"));
    }

    @Bean
    DefaultErrorHandler salesInfoErrorHandler(
            @Value("${shopping.sales.consumer.retry-interval:1s}") Duration retryInterval) {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        new FixedBackOff(
                                retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        // 기본 fatal 목록(ClassCastException 등)을 끈다. 해석·검증 실패는 listener 안에서 건너뛴다.
        handler.setClassifications(Map.of(), true);
        return handler;
    }
}
