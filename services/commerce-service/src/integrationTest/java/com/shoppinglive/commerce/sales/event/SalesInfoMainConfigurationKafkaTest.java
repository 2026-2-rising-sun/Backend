package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

class SalesInfoMainConfigurationKafkaTest {
    @Test
    @DisplayName("main 설정으로 실제 Kafka에 타입 헤더 없는 판매정보를 보내고 중복 설정은 거부한다")
    void publishesWithMainConfigurationAndRejectsMixedConfiguration() throws Exception {
        String topic = "sales-config-" + UUID.randomUUID();
        var broker = new EmbeddedKafkaKraftBroker(1, 1, topic);
        broker.brokerListProperty("shoppinglive.test.kafka-servers");
        broker.afterPropertiesSet();
        try {
            // integrationTest의 application.yml이 운영 producer 설정을 가리지 않게 원본을 지정한다.
            var contextRunner =
                    new ApplicationContextRunner()
                            .withInitializer(new ConfigDataApplicationContextInitializer())
                            .withConfiguration(
                                    AutoConfigurations.of(
                                            JacksonAutoConfiguration.class,
                                            KafkaAutoConfiguration.class))
                            .withUserConfiguration(SalesInfoProducerConfiguration.class)
                            .withPropertyValues(
                                    "spring.config.location=file:src/main/resources/application.yml",
                                    "spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
                                    "spring.kafka.admin.auto-create=false");
            contextRunner.run(
                    context -> {
                        assertThat(context).hasNotFailed();
                        var mapper = context.getBean(ObjectMapper.class);
                        var properties =
                                context.getBean(KafkaProperties.class).buildProducerProperties();
                        assertThat(properties).doesNotContainKey("spring.json.add.type.headers");
                        var factory =
                                new DefaultKafkaProducerFactory<
                                        String, EventEnvelope<SalesInfoSnapshot>>(properties);
                        context.getBean(SalesInfoProducerConfiguration.class)
                                .salesEventSerializer(mapper)
                                .customize(factory);
                        var event =
                                SalesInfoEventContract.create(
                                        SalesInfoEventType.InventoryChanged,
                                        Instant.parse("2026-10-17T00:00:00Z"),
                                        new SalesInfoSnapshot(
                                                42,
                                                7,
                                                3,
                                                1000,
                                                SalesInfoStatus.SOLD_OUT,
                                                0,
                                                "KRW"));
                        try (var consumer =
                                new KafkaConsumer<String, String>(
                                        Map.of(
                                                "bootstrap.servers",
                                                broker.getBrokersAsString(),
                                                "group.id",
                                                UUID.randomUUID().toString(),
                                                "auto.offset.reset",
                                                "earliest",
                                                "key.deserializer",
                                                StringDeserializer.class,
                                                "value.deserializer",
                                                StringDeserializer.class))) {
                            broker.consumeFromAnEmbeddedTopic(consumer, topic);
                            var template = new KafkaTemplate<>(factory);
                            template.send(topic, "42", event).get(10, TimeUnit.SECONDS);
                            var records = consumer.poll(Duration.ofSeconds(10));
                            assertThat(records.count()).isEqualTo(1);
                            var record = records.iterator().next();
                            assertThat(record.key()).isEqualTo("42");
                            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
                            var json = mapper.readTree(record.value());
                            assertThat(json.path("occurredAt").asText())
                                    .isEqualTo("2026-10-17T00:00:00Z");
                            assertThat(json.at("/payload/productId").asLong()).isEqualTo(7);
                            assertThat(json.at("/payload/status").asText()).isEqualTo("SOLD_OUT");
                        } finally {
                            factory.destroy();
                        }
                    });
            contextRunner
                    .withPropertyValues(
                            "spring.kafka.producer.properties.spring.json.add.type.headers=false")
                    .run(
                            context -> {
                                var properties =
                                        context.getBean(KafkaProperties.class)
                                                .buildProducerProperties();
                                var factory =
                                        new DefaultKafkaProducerFactory<String, Object>(properties);
                                context.getBean(SalesInfoProducerConfiguration.class)
                                        .salesEventSerializer(context.getBean(ObjectMapper.class))
                                        .customize(factory);
                                try {
                                    assertThatThrownBy(factory::createProducer)
                                            .isInstanceOf(IllegalStateException.class)
                                            .hasStackTraceContaining("not both");
                                } finally {
                                    factory.destroy();
                                }
                            });
        } finally {
            broker.destroy();
        }
    }
}
