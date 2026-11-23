package com.shoppinglive.commercecore.order.service;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest;
import com.shoppinglive.commercecore.payment.relay.*;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.*;

/**
 * 실제 승인·환불 결과 소비자를 EmbeddedKafka와 PostgreSQL로 띄운 뒤, 두 소비자 그룹의 offset을 처음으로 되감아 같은 메시지를 다시 소비시킨다.
 * 되감기 전후의 주문 상태, 재고, 환불 intent, outbox 발행 수가 같아야 한다.
 */
@SpringBootTest(
        properties = {
            "commerce.commission-rate=0.1250",
            "commercecore.payment-relay.recovery-enabled=false",
            "commercecore.payment.results-enabled=true",
            "shoppinglive.outbox.relay.enabled=true"
        })
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "payment.execution.results.v1",
            "payment.refund.results.v1",
            "commerce.refund.requests.v1",
            "commerce.payment-group.v1",
            "commerce.sales-info.v1"
        })
class ResultConsumerRewindKafkaPostgresTest extends OrderSheetPostgresSupport {
    private static final String APPROVAL_GROUP = "commercecore-payment-results-v1";
    private static final String REFUND_GROUP = "commercecore-common-refund-results-v1";
    private static final String APPROVAL_TOPIC = "payment.execution.results.v1";
    private static final String REFUND_TOPIC = "payment.refund.results.v1";

    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    @Autowired PaymentRelayDispatcher relayDispatcher;
    @Autowired KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean PaymentRelayService relay;
    @MockitoSpyBean OrderTimeSource time;
    @MockitoBean PaymentRelayClient remote;
    @Autowired ObjectMapper mapper;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrap;

    @Test
    @DisplayName("결과 소비자 offset을 처음으로 되감아 다시 소비해도 주문·재고·환불 결과는 한 번만 반영된다")
    void rewindingResultConsumersDoesNotApplyTwice() throws Exception {
        when(remote.create(any()))
                .thenAnswer(
                        i -> {
                            Create request = i.getArgument(0);
                            return new Reply<>(
                                    201,
                                    true,
                                    new Created(
                                            7001L,
                                            "CREATED",
                                            request.orderRef() + "-1",
                                            request.amount(),
                                            request.expiresAt()),
                                    null,
                                    null);
                        });
        var created =
                relay.create(
                        MEMBER_A,
                        "rewind-relay",
                        new RelayOrderRequest(
                                List.of(new CreateOrderSheetRequest.Item(501L, 2)),
                                null,
                                "구매자",
                                "01012345678",
                                8000L,
                                null,
                                null));
        String number = created.number();
        relayDispatcher.dispatch(relay.prepare(MEMBER_A, number));
        relay.acceptApproval(
                MEMBER_A, number, "approval", new Approval(MEMBER_A, "key", number + "-1", 8000));
        doReturn(
                        groups.findByGroupNumberAndMemberId(number, MEMBER_A)
                                .orElseThrow()
                                .getProcessingExpiresAt())
                .when(time)
                .now();

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "rewind-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (var consumer =
                new KafkaConsumer<String, String>(
                        props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("commerce.refund.requests.v1"));

            // 1) 승인 중복 전송 -> 환불 요청 outbox 1건
            var approved =
                    EventEnvelope.create(
                            "PaymentApproved",
                            1,
                            "payment-service",
                            number,
                            Instant.now(),
                            new Approved(7001, number, 8000, Instant.now()));
            String json = mapper.writeValueAsString(approved);
            clearInvocations(relay);
            kafka.send(APPROVAL_TOPIC, number, json).get(10, TimeUnit.SECONDS);
            kafka.send(APPROVAL_TOPIC, number, json).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                verify(relay, atLeast(2)).approved(any());
                                assertThat(countRefundIntents()).isEqualTo(1);
                                assertThat(countPublishedRefundRequested()).isEqualTo(1);
                            });

            // 2) 발행된 환불 요청에서 refundRequestId를 얻는다
            ConsumerRecord<String, String> outgoing = null;
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (outgoing == null && System.nanoTime() < end)
                for (var record : consumer.poll(Duration.ofMillis(200)))
                    if (number.equals(record.key())) outgoing = record;
            assertThat(outgoing).isNotNull();
            var payload = mapper.readTree(outgoing.value());
            ObjectNode resultPayload = (ObjectNode) payload.path("payload").deepCopy();
            resultPayload
                    .put("executionId", UUID.randomUUID().toString())
                    .put("resultVersion", 1)
                    .put("status", "SUCCEEDED")
                    .put("refundedAmount", 8000)
                    .put("resolvedAt", Instant.now().toString())
                    .putNull("nextActionAt")
                    .putNull("failureCode");
            String refund =
                    mapper.writeValueAsString(
                            EventEnvelope.create(
                                    "RefundResultRecorded",
                                    1,
                                    "payment-service",
                                    number,
                                    Instant.now(),
                                    resultPayload));

            // 3) 환불 결과 중복 전송 -> COMPLETED 한 번
            kafka.send(REFUND_TOPIC, number, refund).get(10, TimeUnit.SECONDS);
            kafka.send(REFUND_TOPIC, number, refund).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () ->
                                    assertThat(relay.state(MEMBER_A, number).refundStatus())
                                            .isEqualTo("COMPLETED"));

            var before = snapshot(number);
            assertThat(before.orderStatus()).isEqualTo("CANCELLED");
            assertThat(before.available()).isEqualTo(20);
            assertThat(before.reserved()).isZero();

            // 4) 소비자를 멈추고 두 그룹 offset을 처음으로 되감은 뒤 다시 시작한다
            registry.getListenerContainers().forEach(MessageListenerContainer::stop);
            rewind(APPROVAL_GROUP, APPROVAL_TOPIC, props);
            rewind(REFUND_GROUP, REFUND_TOPIC, props);
            clearInvocations(relay);
            registry.getListenerContainers().forEach(MessageListenerContainer::start);

            // 5) 같은 메시지가 다시 소비되고 두 그룹이 끝까지 따라잡을 때까지 기다린다
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(
                            () -> {
                                verify(relay, atLeast(2)).approved(any());
                                assertThat(caughtUp(APPROVAL_GROUP, APPROVAL_TOPIC, props))
                                        .isTrue();
                                assertThat(caughtUp(REFUND_GROUP, REFUND_TOPIC, props)).isTrue();
                            });

            // 6) 되감기 뒤에도 상태는 그대로다
            assertThat(snapshot(number)).isEqualTo(before);
        }
    }

    private record Snapshot(
            String orderStatus,
            String refundStatus,
            long refundIntents,
            long publishedRefundRequested,
            int available,
            int reserved) {}

    private Snapshot snapshot(String number) {
        var state = relay.state(MEMBER_A, number);
        return new Snapshot(
                state.orderStatus(),
                state.refundStatus(),
                countRefundIntents(),
                countPublishedRefundRequested(),
                available(501),
                reserved(501));
    }

    private long countRefundIntents() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM core_refund_intent WHERE purpose='SYSTEM_COMPENSATION'",
                Long.class);
    }

    private long countPublishedRefundRequested() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundRequested' AND published_at IS NOT NULL",
                Long.class);
    }

    private void rewind(String group, String topic, Map<String, Object> props) throws Exception {
        Map<TopicPartition, Long> begin;
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            var partitions =
                    consumer.partitionsFor(topic).stream()
                            .map(p -> new TopicPartition(topic, p.partition()))
                            .toList();
            begin = consumer.beginningOffsets(partitions);
        }
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> target =
                new HashMap<>();
        begin.forEach(
                (tp, off) ->
                        target.put(
                                tp, new org.apache.kafka.clients.consumer.OffsetAndMetadata(off)));
        try (var admin = admin()) {
            admin.alterConsumerGroupOffsets(group, target).all().get(20, TimeUnit.SECONDS);
        }
    }

    private boolean caughtUp(String group, String topic, Map<String, Object> props)
            throws Exception {
        try (var admin = admin()) {
            var committed =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(20, TimeUnit.SECONDS);
            var ends =
                    admin.listOffsets(Map.of(new TopicPartition(topic, 0), OffsetSpec.latest()))
                            .all()
                            .get(20, TimeUnit.SECONDS);
            return ends.entrySet().stream()
                    .allMatch(
                            e -> {
                                var c = committed.get(e.getKey());
                                return c != null && c.offset() == e.getValue().offset();
                            });
        }
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
    }
}
