package com.shoppinglive.notification.broadcast.event;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 알림의 Kafka 소비 설정.
 *
 * <p>리스너가 성공해야 offset 이 커밋된다. 그래서 DB 장애로 던지면 같은 메시지를 다시 받아 알림이 유실되지 않는다. 대신 그 파티션은 복구될 때까지 멈춘다 —
 * 알림은 "DB 에 한 줄 넣기" 라 빠르고 1초 늦어도 문제가 없으므로, 유실을 막는 쪽을 택한다.
 *
 * <p>Spring 기본값은 {@code FixedBackOff(0, 9)} 로 <b>간격 없이 10번 연타한 뒤 메시지를 버린다</b>. 그대로 두면 DB 가 잠깐 죽은
 * 동안의 알림이 사라지므로, 간격을 두고 복구될 때까지 재시도하도록 바꾼다.
 *
 * <p>다시 읽어도 실패할 메시지(깨진 JSON·계약 위반)는 리스너가 직접 걸러 건너뛴다. 여기까지 올라오지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class BroadcastConsumerConfiguration {

    @Bean
    DefaultKafkaConsumerFactoryCustomizer notificationConsumerDefaults() {
        return factory ->
                factory.updateConfigs(
                        Map.of(
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest"));
    }

    @Bean
    DefaultErrorHandler notificationErrorHandler(
            @Value("${notification.consumer.retry-interval:1s}") Duration retryInterval) {
        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        new FixedBackOff(
                                retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(), true);
        return handler;
    }
}
