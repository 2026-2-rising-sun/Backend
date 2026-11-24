package com.shoppinglive.commercecore.payment.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    CommonRefundResultConsumer commonRefundResultConsumer(
            com.shoppinglive.commercecore.payment.service.KafkaRefundService service,
            ObjectMapper mapper) {
        return new CommonRefundResultConsumer(service, mapper);
    }

    public static class CommonRefundResultConsumer {
        private final com.shoppinglive.commercecore.payment.service.KafkaRefundService service;
        private final ObjectMapper mapper;

        CommonRefundResultConsumer(
                com.shoppinglive.commercecore.payment.service.KafkaRefundService service,
                ObjectMapper mapper) {
            this.service = service;
            this.mapper = mapper;
        }

        @KafkaListener(
                topics = "payment.refund.results.v1",
                groupId = "commercecore-common-refund-results-v1",
                containerFactory = "paymentResultListenerContainerFactory",
                autoStartup = "${commercecore.payment.results-enabled:true}")
        public void refund(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record)
                throws Exception {
            service.result(record.key(), mapper.readTree(record.value()));
        }
    }

    @Bean
    PaymentResultConsumer paymentResultConsumer(
            ObjectMapper mapper,
            com.shoppinglive.commercecore.payment.relay.PaymentRelayService relay) {
        return new PaymentResultConsumer(mapper, relay);
    }

    public static class PaymentResultConsumer {
        private final ObjectMapper mapper;
        private final com.shoppinglive.commercecore.payment.relay.PaymentRelayService relay;

        PaymentResultConsumer(
                ObjectMapper mapper,
                com.shoppinglive.commercecore.payment.relay.PaymentRelayService relay) {
            this.mapper = mapper;
            this.relay = relay;
        }

        @KafkaListener(
                topics = "payment.execution.results.v1",
                groupId = "commercecore-payment-results-v1",
                containerFactory = "paymentResultListenerContainerFactory",
                autoStartup = "${commercecore.payment.results-enabled:true}")
        public void payment(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record)
                throws Exception {
            String json = record.value();
            if ("PaymentApproved".equals(mapper.readTree(json).path("eventType").asText())) {
                EventEnvelope<
                                com.shoppinglive.commercecore.payment.relay.PaymentRelayContract
                                        .Approved>
                        event = mapper.readValue(json, new TypeReference<>() {});
                validate(event, "PaymentApproved", event.payload().orderRef());
                if (!event.aggregateId().equals(record.key()))
                    throw new IllegalArgumentException("승인 이벤트 키가 주문 참조와 다릅니다.");
                relay.approved(event.payload());
                return;
            }
            throw new IllegalArgumentException("지원하지 않는 결제 결과 이벤트입니다.");
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
