package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

class SalesInfoProducerConfigurationTest {
    @Test
    @DisplayName("실제 애플리케이션 설정으로 판매정보를 ISO 시각과 타입 헤더 없이 직렬화한다")
    void serializesSalesEventWithApplicationProperties() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(
                        AutoConfigurations.of(
                                JacksonAutoConfiguration.class, KafkaAutoConfiguration.class))
                .withUserConfiguration(SalesInfoProducerConfiguration.class)
                .withPropertyValues("spring.kafka.admin.auto-create=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var properties =
                                    context.getBean(KafkaProperties.class)
                                            .buildProducerProperties();
                            var mapper = context.getBean(ObjectMapper.class);
                            var factory =
                                    new DefaultKafkaProducerFactory<
                                            String, EventEnvelope<SalesInfoSnapshot>>(properties);
                            context.getBean(SalesInfoProducerConfiguration.class)
                                    .salesEventSerializer(mapper)
                                    .customize(factory);
                            var serializer = factory.getValueSerializer();
                            assertThat(serializer).isNotNull();

                            var occurredAt = Instant.parse("2026-10-17T00:00:00Z");
                            var event =
                                    SalesInfoEventContract.create(
                                            SalesInfoEventType.InventoryChanged,
                                            occurredAt,
                                            new SalesInfoSnapshot(
                                                    42,
                                                    7,
                                                    3,
                                                    1000,
                                                    SalesInfoStatus.SOLD_OUT,
                                                    0,
                                                    "KRW"));
                            var headers = new RecordHeaders();
                            var json =
                                    mapper.readTree(
                                            serializer.serialize(
                                                    SalesInfoEventContract.TOPIC_NAME,
                                                    headers,
                                                    event));
                            assertThat(json.path("occurredAt").asText())
                                    .isEqualTo(occurredAt.toString());
                            assertThat(json.path("payload").path("productId").asLong())
                                    .isEqualTo(7);
                            assertThat(json.path("payload").path("status").asText())
                                    .isEqualTo("SOLD_OUT");
                            assertThat(headers.toArray()).isEmpty();
                        });
    }
}
