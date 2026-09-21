package com.shoppinglive.common.kafka;

import com.shoppinglive.contracts.events.DomainEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Baseline producer/consumer settings shared by every service. Import this from a service
 * configuration once the service actually talks to Kafka; connection details come from
 * {@code spring.kafka.*} in the service's own application.yml.
 */
@Configuration
public class KafkaCommonConfig {

    @Bean
    public DefaultKafkaProducerFactoryCustomizer shoppingliveProducerDefaults() {
        return factory -> factory.updateConfigs(java.util.Map.of(
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.RETRIES_CONFIG, 3));
    }

    @Bean
    public DefaultKafkaConsumerFactoryCustomizer shoppingliveConsumerDefaults() {
        return factory -> factory.updateConfigs(java.util.Map.of(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
    }

    @Bean
    public KafkaTemplate<String, DomainEvent> domainEventKafkaTemplate(
            ProducerFactory<String, DomainEvent> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
