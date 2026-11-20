package com.shoppinglive.commercecore.order.service;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.order.dto.CreateOrderSheetRequest;
import com.shoppinglive.commercecore.payment.relay.*;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.*;

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
class PaymentRelayKafkaPostgresTest extends OrderSheetPostgresSupport {
    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    @Autowired PaymentRelayDispatcher relayDispatcher;
    @MockitoSpyBean PaymentRelayService relay;
    @MockitoSpyBean OrderTimeSource time;
    @MockitoBean PaymentRelayClient remote;
    @Autowired ObjectMapper mapper;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrap;

    @Test
    @DisplayName("새 승인 중복을 실제 Kafka로 소비하고 늦은 환불 outbox를 발행한 뒤 환불 결과를 멱등 기록한다")
    void approvedAndRefundEventsUseRealKafkaAndPostgres() throws Exception {
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
                        "kafka-relay",
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
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-refund-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumer =
                new KafkaConsumer<String, String>(
                        props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("commerce.refund.requests.v1"));
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
            kafka.send("payment.execution.results.v1", number, json).get(10, TimeUnit.SECONDS);
            kafka.send("payment.execution.results.v1", number, json).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () -> {
                                verify(relay, times(2)).approved(any());
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM core_refund_intent WHERE purpose='SYSTEM_COMPENSATION'",
                                                        Long.class))
                                        .isEqualTo(1);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundRequested' AND published_at IS NOT NULL",
                                                        Long.class))
                                        .isEqualTo(1);
                            });
            ConsumerRecord<String, String> outgoing = null;
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (outgoing == null && System.nanoTime() < end)
                for (var record : consumer.poll(Duration.ofMillis(200)))
                    if (number.equals(record.key())) outgoing = record;
            assertThat(outgoing).isNotNull();
            var payload = mapper.readTree(outgoing.value());
            assertThat(payload.path("eventType").asText()).isEqualTo("RefundRequested");
            assertThat(payload.path("payload").path("refundRequestId").asText())
                    .matches("[0-9a-f-]{36}");
            assertThat(payload.path("payload").path("reason").asText())
                    .isEqualTo("APPROVAL_UNKNOWN");
            var resultPayload =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            payload.path("payload").deepCopy();
            resultPayload
                    .put("executionId", UUID.randomUUID().toString())
                    .put("resultVersion", 1)
                    .put("status", "SUCCEEDED")
                    .put("refundedAmount", 8000)
                    .put("resolvedAt", Instant.now().toString())
                    .putNull("nextActionAt")
                    .putNull("failureCode");
            var succeeded =
                    EventEnvelope.create(
                            "RefundResultRecorded",
                            1,
                            "payment-service",
                            number,
                            Instant.now(),
                            resultPayload);
            String refund = mapper.writeValueAsString(succeeded);
            kafka.send("payment.refund.results.v1", number, refund).get(10, TimeUnit.SECONDS);
            kafka.send("payment.refund.results.v1", number, refund).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(
                            () ->
                                    assertThat(relay.state(MEMBER_A, number).refundStatus())
                                            .isEqualTo("COMPLETED"));
            assertThat(relay.state(MEMBER_A, number).orderStatus()).isEqualTo("CANCELLED");
            assertThat(available(501)).isEqualTo(20);
            assertThat(reserved(501)).isZero();
        }
    }
}
