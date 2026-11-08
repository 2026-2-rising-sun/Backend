package com.shoppinglive.commercecore.payment.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.service.PaymentResultHandler;
import com.shoppinglive.common.events.EventEnvelope;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
public class PaymentEventConsumerConfiguration {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> paymentResultListenerContainerFactory(
            KafkaProperties properties) {
        Map<String, Object> config =
                new java.util.HashMap<>(properties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Core 로컬 반영 실패를 성공으로 ack하지 않고 같은 결과를 재전달한다.
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }

    @Bean
    PaymentResultConsumer paymentResultConsumer(ObjectMapper mapper, PaymentResultHandler handler) {
        return new PaymentResultConsumer(mapper, handler);
    }

    public static class PaymentResultConsumer {
        private final ObjectMapper mapper;
        private final PaymentResultHandler handler;

        PaymentResultConsumer(ObjectMapper mapper, PaymentResultHandler handler) {
            this.mapper = mapper;
            this.handler = handler;
        }

        @KafkaListener(
                topics = "payment.execution-results.v1",
                groupId = "commercecore-payment-results-v1",
                containerFactory = "paymentResultListenerContainerFactory",
                autoStartup = "${commercecore.payment.results-enabled:true}")
        public void payment(String json) throws Exception {
            EventEnvelope<PaymentExecution> event =
                    mapper.readValue(json, new TypeReference<>() {});
            validate(event, "PaymentExecutionResolved", event.payload().requestId());
            if (!event.payload().status().isConfirmedOutcome())
                throw new IllegalArgumentException("확정된 결제 결과 이벤트가 필요합니다.");
            handler.payment(event.payload());
        }

        @KafkaListener(
                topics = "payment.refund-results.v1",
                groupId = "commercecore-refund-results-v1",
                containerFactory = "paymentResultListenerContainerFactory",
                autoStartup = "${commercecore.payment.results-enabled:true}")
        public void refund(String json) throws Exception {
            EventEnvelope<RefundExecution> event = mapper.readValue(json, new TypeReference<>() {});
            validate(event, "RefundExecutionResolved", event.payload().requestId());
            var status = event.payload().status();
            if (status != com.shoppinglive.commercecore.payment.entity.RefundStatus.SUCCESS
                    && status != com.shoppinglive.commercecore.payment.entity.RefundStatus.FAILED)
                throw new IllegalArgumentException("확정된 환불 결과 이벤트가 필요합니다.");
            handler.refund(event.payload());
        }

        private void validate(EventEnvelope<?> e, String type, String requestId) {
            if (!type.equals(e.eventType())
                    || e.schemaVersion() != 1
                    || !"payment-service".equals(e.source())
                    || !e.aggregateId().equals(requestId))
                throw new IllegalArgumentException("Payment 결과 이벤트 봉투가 올바르지 않습니다.");
        }
    }
}
