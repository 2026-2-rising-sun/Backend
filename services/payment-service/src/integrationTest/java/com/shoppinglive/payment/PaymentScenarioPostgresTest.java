package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.payment.dto.CreatePaymentRequest;
import com.shoppinglive.payment.payment.dto.SellerAmountRequest;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import com.shoppinglive.payment.payment.service.PaymentService;
import com.shoppinglive.payment.payment.service.UnknownPaymentPoller;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 결제 1차 종료 시나리오: 실제 토스 HTTP 클라이언트를 {@link MockRestServiceServer}에 묶어 생성 → 승인, 거절 → 다시 결제 → 승인, 결과
 * 불명 → 조회 DONE을 한 번씩 돌리고 모든 분개의 차변 합 = 대변 합을 확인한다.
 */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentScenarioPostgresTest {
    static final String SCHEMA =
            "payment_scenario_" + UUID.randomUUID().toString().replace("-", "");
    static final String BUYER = "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13";
    static final String TOSS = "https://toss.test";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("payment.core-service-token", () -> "payment-scenario-postgres-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @TestConfiguration
    static class TossServer {
        final RestClient.Builder builder = RestClient.builder().baseUrl(TOSS);
        final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        @Bean
        MockRestServiceServer tossServer() {
            return server;
        }

        @Bean
        @Primary
        TossPaymentsClient mockedToss() {
            return new TossPaymentsClient(builder.build(), Duration.ofSeconds(5));
        }
    }

    @Autowired PaymentService payments;
    @Autowired ApprovalService approvals;
    @Autowired UnknownPaymentPoller poller;
    @Autowired MockRestServiceServer toss;
    @Autowired JdbcTemplate jdbc;

    ApproveCommand create(long amount) {
        String orderRef = "OD-" + UUID.randomUUID().toString().substring(0, 13);
        payments.create(
                new CreatePaymentRequest(
                        orderRef,
                        UUID.fromString(BUYER),
                        amount,
                        OffsetDateTime.now().plusMinutes(10),
                        List.of(
                                new SellerAmountRequest(
                                        orderRef + "-S1",
                                        UUID.randomUUID(),
                                        amount,
                                        new BigDecimal("0.1000")))));
        return new ApproveCommand(orderRef, BUYER, "tgen_" + orderRef, orderRef + "-1", amount);
    }

    static String payment(ApproveCommand c, String pgOrderId, String status) {
        return """
                {"paymentKey":"%s","orderId":"%s","status":"%s","totalAmount":%d,
                 "approvedAt":"2026-11-10T14:02:11+09:00","isPartialCancelable":true}
                """
                .formatted(c.paymentKey(), pgOrderId, status, c.amount());
    }

    void expectConfirm(
            ApproveCommand c,
            String pgOrderId,
            org.springframework.test.web.client.ResponseCreator response) {
        toss.expect(requestTo(TOSS + "/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "confirm-" + pgOrderId))
                .andRespond(response);
    }

    String status(ApproveCommand c) {
        return jdbc.queryForObject(
                "SELECT status FROM payment WHERE order_ref = ?", String.class, c.orderRef());
    }

    int journals(ApproveCommand c) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal j JOIN payment p ON j.ref_id = CAST(p.id AS VARCHAR)"
                        + " WHERE j.ref_type = 'PAYMENT_APPROVED' AND p.order_ref = ?",
                Integer.class,
                c.orderRef());
    }

    int outbox(ApproveCommand c) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE message_key = ? AND event_type = 'PaymentApproved'",
                Integer.class,
                c.orderRef());
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-06][PAY-CONFIRM-08][PAY-RECOVER-02][LED-APPROVE-01] 결제 1차 시나리오: 생성 → 승인, 거절 → 다시 결제 → 승인, 결과 불명 → 조회 DONE이 각각 APPROVED·분개 1건·outbox 1행이고 모든 분개는 차변 합 = 대변 합")
    void phaseOneScenario() {
        // MockRestServiceServer는 요청이 시작된 뒤 기대를 더할 수 없어 세 흐름의 토스 응답을 순서대로 먼저 건다
        ApproveCommand first = create(30000);
        ApproveCommand retry = create(45000);
        ApproveCommand retried =
                new ApproveCommand(
                        retry.orderRef(),
                        BUYER,
                        retry.paymentKey() + "-2",
                        retry.orderRef() + "-2",
                        retry.amount());
        ApproveCommand unknown = create(12000);
        expectConfirm(
                first,
                first.pgOrderId(),
                withSuccess(payment(first, first.pgOrderId(), "DONE"), MediaType.APPLICATION_JSON));
        expectConfirm(
                retry,
                retry.pgOrderId(),
                withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도 초과\"}"));
        expectConfirm(
                retried,
                retried.pgOrderId(),
                withSuccess(
                        payment(retried, retried.pgOrderId(), "DONE"), MediaType.APPLICATION_JSON));
        expectConfirm(unknown, unknown.pgOrderId(), withServerError());
        expectConfirm(unknown, unknown.pgOrderId(), withServerError());
        toss.expect(requestTo(TOSS + "/v1/payments/" + unknown.paymentKey()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                payment(unknown, unknown.pgOrderId(), "DONE"),
                                MediaType.APPLICATION_JSON));

        // 1. 생성 → 승인
        assertThat(approvals.approve(first)).isInstanceOf(ApprovalResult.Approved.class);

        // 2. 거절 → 새 결제창 → 승인
        assertThat(approvals.approve(retry)).isInstanceOf(ApprovalResult.Declined.class);
        assertThat(status(retry)).isEqualTo("CREATED");
        assertThat(payments.openCheckout(retry.orderRef()).getPgOrderId())
                .isEqualTo(retried.pgOrderId());
        assertThat(approvals.approve(retried)).isInstanceOf(ApprovalResult.Approved.class);

        // 3. 결과 불명(두 번 모두 5xx) → 조회 DONE
        assertThat(approvals.approve(unknown)).isInstanceOf(ApprovalResult.Pending.class);
        assertThat(status(unknown)).isEqualTo("UNKNOWN");
        jdbc.update(
                "UPDATE payment SET next_check_at = now() WHERE order_ref = ?", unknown.orderRef());
        poller.poll();

        toss.verify();
        for (ApproveCommand c : List.of(first, retry, unknown)) {
            assertThat(status(c)).as(c.orderRef()).isEqualTo("APPROVED");
            assertThat(journals(c)).as(c.orderRef()).isEqualTo(1);
            assertThat(outbox(c)).as(c.orderRef()).isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM (SELECT journal_id FROM ledger_entry GROUP BY journal_id"
                                        + " HAVING sum(debit) <> sum(credit)) unbalanced",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(debit) - sum(credit) FROM ledger_entry", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT sum(debit) FROM ledger_entry", Long.class))
                .isEqualTo(30000 + 45000 + 12000);
    }
}
