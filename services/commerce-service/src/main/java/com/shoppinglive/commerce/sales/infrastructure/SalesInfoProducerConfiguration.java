package com.shoppinglive.commerce.sales.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration(proxyBeanMethods = false)
class SalesInfoProducerConfiguration {
    @Bean
    DefaultKafkaProducerFactoryCustomizer salesEventSerializer(ObjectMapper mapper) {
        return factory -> configure(factory, mapper);
    }

    private static <K, V> void configure(DefaultKafkaProducerFactory<K, V> factory, ObjectMapper mapper) {
        // Use Boot's ISO-8601 Instant representation and omit service-specific Java type headers.
        factory.setValueSerializer(new JsonSerializer<V>(mapper).noTypeInfo());
    }
}
