package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(properties = "commercecore.payment.results-enabled=true")
@EmbeddedKafka(
        partitions = 1,
        topics = {"payment.execution-results.v1", "payment.refund-results.v1"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class PaymentResultKafkaPostgresTest extends RefundTestSupport {
    @Autowired
    @Qualifier("outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    @Autowired RefundIntakeService refunds;
    @Autowired PaymentResultHandler handler;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean(
            name = "boundaryPaymentClient")
    PaymentClient clientSpy;

    @Autowired PaymentClient client;

    @Test
    @DisplayName("독립 Payment의 실제 결과 JSON을 Kafka로 받으면 주문·환불을 한 번만 반영한다")
    void persistedPaymentAndRefundEnvelopesAreConsumedIdempotently() throws Exception {
        var created =
                paymentGroups
                        .create(
                                MEMBER_A,
                                List.of(
                                        new com.shoppinglive.commercecore.order.service
                                                .PaymentGroupService.Selection(
                                                a.getId(), a.getVersion()),
                                        new com.shoppinglive.commercecore.order.service
                                                .PaymentGroupService.Selection(
                                                b.getId(), b.getVersion())),
                                "구매자",
                                "01012345678",
                                25000,
                                "kafka-order")
                        .group();
        var payment = paymentGroups.start(MEMBER_A, created.groupNumber(), "kafka-pay");
        assertThat(
                        paymentServer
                                .context
                                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                                .resolvePayment(payment.paymentId()))
                .isTrue();
        assertThat(paymentGroups.get(MEMBER_A, created.groupNumber()).status().name())
                .isEqualTo("PAYMENT_CONFIRMING");
        send("PaymentExecutionResolved", "payment.execution-results.v1", payment.requestId());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                paymentGroups
                                                        .get(MEMBER_A, created.groupNumber())
                                                        .status()
                                                        .name())
                                        .isEqualTo("PAID"));
        send("PaymentExecutionResolved", "payment.execution-results.v1", payment.requestId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='PaymentSucceeded'",
                                Long.class))
                .isEqualTo(1);
        var request =
                refunds.request(MEMBER_A, created.groupNumber(), "kafka-refund", List.of())
                        .refund();
        submitRefund(request.id());
        assertThat(
                        paymentServer
                                .context
                                .getBean(
                                        com.shoppinglive.payment.service.RefundExecutionService
                                                .class)
                                .execute(request.id()))
                .isTrue();
        var result = client.refund(request.id());
        send("RefundExecutionResolved", "payment.refund-results.v1", result.requestId());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                paymentGroups
                                                        .get(MEMBER_A, created.groupNumber())
                                                        .status()
                                                        .name())
                                        .isEqualTo("REFUNDED"));
        send("RefundExecutionResolved", "payment.refund-results.v1", result.requestId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundCompleted'",
                                Long.class))
                .isEqualTo(1);
        assertThat(stocks.findAll())
                .allSatisfy(
                        stock -> {
                            assertThat(stock.getAvailable()).isEqualTo(5);
                            assertThat(stock.getReserved()).isZero();
                        });
    }

    @Test
    @DisplayName("Kafka 결과가 HTTP 응답보다 먼저 도착해도 늦은 미확정 응답과 중복 결과를 한 번만 반영한다")
    void kafkaResultBeforeHttpResponseIsAppliedOnce() {
        var created =
                paymentGroups
                        .create(
                                MEMBER_A,
                                List.of(
                                        new com.shoppinglive.commercecore.order.service
                                                .PaymentGroupService.Selection(
                                                a.getId(), a.getVersion())),
                                "구매자",
                                "01012345678",
                                20000,
                                "early-kafka-order")
                        .group();
        doAnswer(
                        invocation -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var pending =
                                    (com.shoppinglive.commercecore.payment.dto.PaymentExecution)
                                            invocation.callRealMethod();
                            assertThat(
                                            paymentServer
                                                    .context
                                                    .getBean(
                                                            com.shoppinglive.payment.service
                                                                    .PaymentService.class)
                                                    .resolvePayment(pending.paymentId()))
                                    .isTrue();
                            send(
                                    "PaymentExecutionResolved",
                                    "payment.execution-results.v1",
                                    pending.requestId());
                            await().atMost(Duration.ofSeconds(20))
                                    .untilAsserted(
                                            () ->
                                                    assertThat(
                                                                    paymentGroups
                                                                            .get(
                                                                                    MEMBER_A,
                                                                                    created
                                                                                            .groupNumber())
                                                                            .status()
                                                                            .name())
                                                            .isEqualTo("PAID"));
                            return pending;
                        })
                .when(clientSpy)
                .start(any());
        var result = paymentGroups.start(MEMBER_A, created.groupNumber(), "early-kafka-pay");
        assertThat(result.status().name()).isEqualTo("SUCCESS");
        assertThat(handler.payment(clientSpy.get(result.paymentId()))).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='PaymentSucceeded'",
                                Long.class))
                .isEqualTo(1);
        var order = orders.findByPaymentGroupIdOrderByIdAsc(result.paymentGroupId()).getFirst();
        assertThat(legacyStock(order.getTradeSkuId()).getAvailable()).isEqualTo(3);
        assertThat(legacyStock(order.getTradeSkuId()).getReserved()).isZero();
    }

    void send(String event, String topic, String key) throws Exception {
        String payload =
                paymentServer.jdbc.queryForObject(
                        "SELECT payload FROM event_outbox WHERE event_type=? AND message_key=?",
                        String.class,
                        event,
                        key);
        kafka.send(topic, key, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
