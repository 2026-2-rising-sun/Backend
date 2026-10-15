package com.shoppinglive.commerce.sales.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration(proxyBeanMethods = false)
class SalesInfoProducerConfiguration {
    @Bean
    org.springframework.kafka.core.KafkaTemplate<
                    String, com.shoppinglive.common.events.EventEnvelope<SalesInfoSnapshot>>
            salesInfoKafkaTemplate(
                    org.springframework.kafka.core.ProducerFactory<
                                    String,
                                    com.shoppinglive.common.events.EventEnvelope<SalesInfoSnapshot>>
                            factory) {
        return new org.springframework.kafka.core.KafkaTemplate<>(factory);
    }

    @Bean
    DefaultKafkaProducerFactoryCustomizer salesEventSerializer(ObjectMapper mapper) {
        return factory -> {
            factory.updateConfigs(
                    java.util.Map.of(
                            org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG,
                            "all",
                            org.apache.kafka.clients.producer.ProducerConfig
                                    .ENABLE_IDEMPOTENCE_CONFIG,
                            true,
                            org.apache.kafka.clients.producer.ProducerConfig.RETRIES_CONFIG,
                            3));
            configure(factory, mapper);
        };
    }

    private static <K, V> void configure(
            DefaultKafkaProducerFactory<K, V> factory, ObjectMapper mapper) {
        // Boot의 ISO-8601 시각 표현을 사용하고 서비스 전용 Java 타입 헤더는 제외한다.
        factory.setValueSerializer(new JsonSerializer<V>(mapper).noTypeInfo());
    }
}
