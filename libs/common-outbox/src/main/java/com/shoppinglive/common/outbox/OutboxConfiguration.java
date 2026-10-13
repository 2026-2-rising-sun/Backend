package com.shoppinglive.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Kafka 로 이벤트를 내는 서비스가 {@code @Import} 한다. {@code @AutoConfigurationPackage} 로 이
 * 패키지를 JPA 엔티티·리포지토리 스캔에 더하므로 서비스가 스캔 범위를 따로 고치지 않아도 된다.
 * 서비스는 Flyway 로 {@code event_outbox} 테이블을 만들어야 한다.
 *
 * <p>릴레이는 {@code @Scheduled} 라 서비스에 {@code @EnableScheduling} 이 필요하다.
 * Kafka 없이 도는 테스트는 {@code shoppinglive.outbox.relay.enabled=false} 로 끈다.
 */
@Configuration
@AutoConfigurationPackage
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {

    @Bean
    public EventOutbox eventOutbox(final OutboxMessageRepository repository,
                                   final ObjectMapper objectMapper) {
        return new EventOutbox(repository, objectMapper);
    }

    /**
     * outbox 행은 이미 JSON 문자열이라 값도 문자열 그대로 보낸다.
     * {@code max.block.ms} 를 {@link OutboxProperties#sendTimeout()} 로 맞춰, 브로커 메타데이터를
     * 못 받는 상황에서도 {@code send()} 호출이 producer 기본 60 초가 아닌 설정값에서 풀리게 한다.
     */
    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            final ProducerFactory<String, String> producerFactory,
            final OutboxProperties properties) {
        return new KafkaTemplate<>(producerFactory, Map.of(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.sendTimeout().toMillis()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "shoppinglive.outbox.relay", name = "enabled",
        havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(final OutboxMessageRepository repository,
                                   final KafkaTemplate<String, String> outboxKafkaTemplate,
                                   final OutboxProperties properties) {
        return new OutboxRelay(repository, outboxKafkaTemplate, properties);
    }
}
