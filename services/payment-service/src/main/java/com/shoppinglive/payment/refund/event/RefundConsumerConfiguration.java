package com.shoppinglive.payment.refund.event;

import com.shoppinglive.common.kafka.ConsumerErrorHandlers;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * 환불 요청 소비의 실패 처리. 오류 처리 규칙은 공통 {@link ConsumerErrorHandlers}가 정하고, 이 클래스는 Payment의 DLT 접미사와 재시도
 * 간격만 정한다. notification-service와 Core 결과 소비자도 같은 규칙을 쓴다.
 *
 * <p>DLT 메시지는 자동으로 다시 처리하지 않는다. 사람이 원인을 보고 되돌린다. 업무 실패(결제 없음 등)는 예외가 아니라 FAILED 결과로 답한다.
 */
@Configuration(proxyBeanMethods = false)
public class RefundConsumerConfiguration {

    /** 원본 토픽 뒤에 붙인다. 서비스 이름이 있어야 다른 소비자의 실패 메시지와 섞이지 않는다. */
    static final String DLT_SUFFIX = ".payment.dlt";

    @Bean
    DefaultKafkaConsumerFactoryCustomizer eventConsumerDefaults() {
        return factory ->
                factory.updateConfigs(
                        Map.of(
                                // 처리가 성공해야 "읽었다" 고 기록한다. 그래서 던지면 같은 메시지를 다시 받는다.
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                // 구독을 시작하기 전에 쌓인 이벤트도 받는다.
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest"));
    }

    @Bean
    DefaultErrorHandler eventErrorHandler(
            KafkaOperations<?, ?> kafka,
            @Value("${payment.refunds.consumer.retry-interval:1s}") Duration retryInterval) {
        // 요청 계약 위반(InvalidRefundRequestException)은 재시도 없이 DLT로 간다.
        return ConsumerErrorHandlers.deadLettering(kafka, DLT_SUFFIX, retryInterval);
    }
}
