package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

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
