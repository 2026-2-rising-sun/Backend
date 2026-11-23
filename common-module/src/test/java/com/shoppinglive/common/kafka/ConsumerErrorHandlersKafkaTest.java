package com.shoppinglive.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(ConsumerErrorHandlersKafkaTest.Config.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"dlt-proof.orders.v1", "dlt-proof.orders.v1.test.dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ConsumerErrorHandlersKafkaTest {
    static final String TOPIC = "dlt-proof.orders.v1";
    static final String DLT = "dlt-proof.orders.v1.test.dlt";
    static final ConcurrentLinkedQueue<String> processed = new ConcurrentLinkedQueue<>();
    static final AtomicInteger dbAttempts = new AtomicInteger();
    static final AtomicInteger dltFailures = new AtomicInteger();
    static final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> attempts =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired KafkaTemplate<String, String> template;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrap;

    @Test
    @DisplayName("계약 위반 메시지는 재시도 없이 DLT로 가고 뒤의 정상 메시지는 반영된다")
    void contractViolationGoesToDltAndNextMessageIsApplied() throws Exception {
        template.send(TOPIC, "k1", "bad-contract").get();
        template.send(TOPIC, "k2", "good-contract").get();
        awaitProcessed("good-contract");
        assertThat(processed).doesNotContain("bad-contract");
        assertThat(attempts.get("bad-contract").get()).isEqualTo(1);
        var dead = readDlt("bad-contract");
        assertThat(headerValue(dead, "kafka_dlt-original-offset")).isNotNull();
        assertThat(dead.partition()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("그 밖의 실패는 제한 재시도 뒤 DLT로 가고 뒤의 정상 메시지는 반영된다")
    void genericFailureIsRetriedThenDeadLettered() throws Exception {
        template.send(TOPIC, "k3", "boom").get();
        template.send(TOPIC, "k4", "good-after-boom").get();
        awaitProcessed("good-after-boom");
        readDlt("boom");
        assertThat(attempts.get("boom").get()).isEqualTo(6);
    }

    @Test
    @DisplayName("DB 연결 장애는 DLT로 보내지 않고 복구될 때까지 재시도한다")
    void dbOutageIsRetriedUntilRecovered() throws Exception {
        template.send(TOPIC, "k5", "flaky-db").get();
        template.send(TOPIC, "k6", "good-after-db").get();
        awaitProcessed("flaky-db");
        awaitProcessed("good-after-db");
        assertThat(dbAttempts.get()).isGreaterThan(3);
    }

    @Test
    @DisplayName("DLT 전송이 실패하면 원본 offset을 넘기지 않고, 복구된 뒤 DLT로 보내고 뒤 메시지를 처리한다")
    void dltSendFailureKeepsOffsetAndRecoversBeforeNextMessage() throws Exception {
        dltFailures.set(2);
        template.send(TOPIC, "k9", "bad-dlt-fail").get();
        template.send(TOPIC, "k10", "good-after-dlt-fail").get();
        awaitProcessed("good-after-dlt-fail");
        var dead = readDlt("bad-dlt-fail");
        assertThat(dead.value()).isEqualTo("bad-dlt-fail");
        // 처음 시도 1회와 DLT 전송 실패 2회: 같은 메시지를 세 번 받았다
        assertThat(attempts.get("bad-dlt-fail").get()).isGreaterThanOrEqualTo(3);
        assertThat(dltFailures.get()).isZero();
    }

    @Test
    @DisplayName("로그에는 토픽·파티션·offset·원인 클래스만 남고 메시지 원문은 남지 않으며 DLT 메시지에는 원문이 있다")
    void logDoesNotContainRawPayload() throws Exception {
        var logger =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger(ConsumerErrorHandlers.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            template.send(TOPIC, "k11", "boom-SECRET-7731").get();
            template.send(TOPIC, "k12", "json-SECRET-8842").get();
            template.send(TOPIC, "k13", "good-after-secret").get();
            awaitProcessed("good-after-secret");
            assertThat(readDlt("boom-SECRET-7731").value()).contains("SECRET-7731");
            assertThat(readDlt("json-SECRET-8842").value()).contains("SECRET-8842");
            var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(m -> m.contains(ConsumerErrorHandlers.DLT_MARKER));
            assertThat(messages).noneMatch(m -> m.contains("SECRET"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private void awaitProcessed(String value) throws InterruptedException {
        long until = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (!processed.contains(value)) {
            if (System.currentTimeMillis() > until) throw new AssertionError("처리되지 않음: " + value);
            Thread.sleep(50);
        }
    }

    private ConsumerRecord<String, String> readDlt(String value) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long until = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(DLT));
            while (System.currentTimeMillis() < until) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (value.equals(record.value())) return record;
                }
            }
        }
        throw new AssertionError("DLT에 없음: " + value);
    }

    private static String headerValue(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    static class Config {
        @Bean
        KafkaTemplate<String, String> template(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            return new KafkaTemplate<>(
                    new DefaultKafkaProducerFactory<>(
                            Map.of(
                                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                            StringSerializer.class,
                                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                            StringSerializer.class)));
        }

        @Bean
        KafkaTemplate<String, String> dltTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            return new KafkaTemplate<>(
                    new DefaultKafkaProducerFactory<>(
                            Map.of(
                                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                                            StringSerializer.class,
                                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                                            StringSerializer.class))) {
                @Override
                public CompletableFuture<SendResult<String, String>> send(
                        ProducerRecord<String, String> record) {
                    if (dltFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0)
                        return CompletableFuture.failedFuture(new KafkaException("DLT 전송 실패(테스트)"));
                    return super.send(record);
                }
            };
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> listenerFactory(
                @org.springframework.beans.factory.annotation.Qualifier("dltTemplate")
                        KafkaTemplate<String, String> dltTemplate,
                @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(
                    new DefaultKafkaConsumerFactory<>(
                            Map.of(
                                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                                    bootstrap,
                                    ConsumerConfig.GROUP_ID_CONFIG,
                                    "dlt-proof",
                                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                    "earliest",
                                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                    false,
                                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                                    StringDeserializer.class,
                                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                                    StringDeserializer.class)));
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.setCommonErrorHandler(
                    ConsumerErrorHandlers.deadLettering(
                            dltTemplate, ".test.dlt", Duration.ofMillis(10)));
            return factory;
        }

        @Bean
        Listener listener() {
            return new Listener();
        }
    }

    static class Listener {
        @KafkaListener(topics = TOPIC, groupId = "dlt-proof", containerFactory = "listenerFactory")
        void on(String value) throws Exception {
            attempts.computeIfAbsent(value, k -> new AtomicInteger()).incrementAndGet();
            if (value.startsWith("bad-")) throw new ContractViolationException("계약 위반");
            if (value.startsWith("boom-")) throw new IllegalStateException("코드 실패: " + value);
            if (value.startsWith("json-"))
                throw new com.fasterxml.jackson.core.JsonParseException(null, "bad token " + value);
            switch (value) {
                case "boom" -> throw new IllegalStateException("코드 실패");
                case "flaky-db" -> {
                    if (dbAttempts.incrementAndGet() <= 3)
                        throw new TransientDataAccessResourceException(
                                "db down", new SQLTransientConnectionException("down"));
                    processed.add(value);
                }
                default -> processed.add(value);
            }
        }
    }
}
