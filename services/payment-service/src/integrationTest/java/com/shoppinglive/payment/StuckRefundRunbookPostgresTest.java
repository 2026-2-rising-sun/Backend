package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.service.PaymentOutcomeService;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.service.compensation.CompensationRetryJob;
import com.shoppinglive.payment.refund.service.user.UserRefundExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 운영 문서({@code docs/design-docs/services/payment/operations/stuck-refund.md})의 SQL을 그대로 실행해 멈춘 고객
 * 환불을 다시 잇는지 확인한다(#369). 토스는 가짜다. 실제 토스를 부르지 않는다.
 */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class StuckRefundRunbookPostgresTest extends RefundPostgresTestSupport {
    static final String SCHEMA = "payment_runbook_" + UUID.randomUUID().toString().replace("-", "");
    static final String COMPENSATION_REASON = PaymentOutcomeService.COMPENSATION_REASON;
    static final String RUNBOOK = "docs/design-docs/services/payment/operations/stuck-refund.md";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", StuckRefundRunbookPostgresTest::url);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        p.add("payment.core-service-token", () -> "payment-runbook-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    static String url() {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }

    @Autowired UserRefundExecutor executor;
    @Autowired CompensationRetryJob retryJob;
    @MockitoBean TossPaymentsClient toss;

    @BeforeEach
    void isolate() {
        jdbc.update(
                "UPDATE refund SET next_attempt_at = NULL WHERE status IN ('PROCESSING', 'UNKNOWN')");
    }

    @Test
    @DisplayName("[REF-EXEC-06] 재개 SQL은 재시도 시각과 조회 마감만 바꾸고 금액·상태는 그대로 둔다")
    void resumeSqlChangesOnlyScheduleAndDeadline() throws Exception {
        Refund refund = stuckUserRefund("RB-KEEP");

        jdbc.update(sql("resume-stuck-refund"));

        Map<String, Object> row = refund(refund.getRefundRequestId());
        assertThat(row)
                .containsEntry("status", "UNKNOWN")
                .containsEntry("amount", 4000L)
                .containsEntry("refunded_amount", 0L)
                .containsEntry("result_version", 0);
        assertThat(row.get("next_attempt_at")).isNotNull();
        assertThat(row.get("check_deadline_at")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT check_deadline_at > now() FROM refund WHERE id = ?",
                                Boolean.class,
                                refund.getId()))
                .isTrue();
    }

    @Test
    @DisplayName("[REF-EXEC-04] 재개하면 실행기가 토스 조회부터 다시 하고 같은 멱등 키로만 취소를 보낸다")
    void resumedRefundQueriesFirstThenResendsSameKey() throws Exception {
        Refund refund = stuckUserRefund("RB-RESUME");
        jdbc.update(sql("resume-stuck-refund"));

        // 재개 직후 첫 실행: 조회가 실패하면 멈추지 않고 다음 실행을 예약한다
        when(toss.getPayment(pk("RB-RESUME"))).thenReturn(new TossResult.Unknown("시간 초과"));
        executor.retryDue();
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        assertThat(refund(refund.getRefundRequestId()).get("next_attempt_at")).isNotNull();
        // 준비 단계의 취소 1번만 있어야 한다. 조회 실패로 재요청하지 않았다
        verify(toss, times(1)).cancel(any(), any(), any(), any());

        // 다음 실행: 조회에 취소가 없으니 같은 멱등 키와 같은 본문으로 다시 보낸다
        jdbc.update(
                "UPDATE refund SET next_attempt_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
        when(toss.getPayment(pk("RB-RESUME")))
                .thenReturn(new TossResult.Success(tossPayment("RB-RESUME", false)));
        when(toss.cancel(any(), any(), any(), any())).thenReturn(canceled("RB-RESUME", refund));
        executor.retryDue();

        InOrder order = inOrder(toss);
        order.verify(toss, atLeastOnce()).getPayment(pk("RB-RESUME"));
        order.verify(toss)
                .cancel(pk("RB-RESUME"), reason(refund), refund.getRefundRequestId(), 4000L);
        verify(toss, times(2)).cancel(any(), any(), any(), any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @DisplayName("[REF-EXEC-04] 서버를 다시 띄운 뒤에도 재개된 고객 환불이 이어서 처리된다")
    void resumedRefundContinuesAfterRestart() throws Exception {
        Refund refund = stuckUserRefund("RB-RESTART");
        jdbc.update(sql("resume-stuck-refund"));
        // 이전 프로세스가 토스 호출 중에 죽었다고 보고 선점(next_attempt_at)만 지난 것으로 둔다
        jdbc.update(
                "UPDATE refund SET next_attempt_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());

        try (ConfigurableApplicationContext restarted = restart()) {
            TossPaymentsClient restartedToss = restarted.getBean(TossPaymentsClient.class);
            when(restartedToss.getPayment(pk("RB-RESTART")))
                    .thenReturn(new TossResult.Success(tossPayment("RB-RESTART", false)));
            when(restartedToss.cancel(any(), any(), any(), any()))
                    .thenReturn(canceled("RB-RESTART", refund));

            restarted.getBean(UserRefundExecutor.class).retryDue();

            verify(restartedToss, times(1))
                    .cancel(pk("RB-RESTART"), reason(refund), refund.getRefundRequestId(), 4000L);
        }
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @DisplayName("[REF-LATE-02] 재개된 시스템 보상은 토스 조회부터 하고 취소가 없을 때만 같은 키로 다시 보내며, 재개 즉시 멈추지 않는다")
    void resumedCompensationQueriesFirstThenResendsSameKey() throws Exception {
        String orderRef = "RB-COMP-" + UUID.randomUUID().toString().substring(0, 8);
        Refund refund = stuckCompensation(orderRef);
        String key = "cancel-PO-" + orderRef;
        jdbc.update(sql("resume-stuck-refund"));

        Map<String, Object> row = refund(refund.getRefundRequestId());
        assertThat(row)
                .containsEntry("status", "UNKNOWN")
                .containsEntry("amount", AMOUNT)
                .containsEntry("refunded_amount", 0L)
                .containsEntry("result_version", 0);
        assertThat(row.get("next_attempt_at")).isNotNull();

        // 재개 직후 첫 실행: 조회가 실패하면 멈추지 않고 다음 실행을 예약한다. 취소는 다시 보내지 않는다
        when(toss.getPayment(pk(orderRef))).thenReturn(new TossResult.Unknown("시간 초과"));
        makeDue(refund);
        retryJob.run();
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        assertThat(refund(refund.getRefundRequestId()).get("next_attempt_at")).isNotNull();
        verify(toss, times(1)).cancel(any(), any(), any());

        // 다음 실행: 조회에 이 보상의 취소가 없으니 같은 키로 다시 보낸다
        when(toss.getPayment(pk(orderRef)))
                .thenReturn(new TossResult.Success(paymentWith(orderRef, "DONE", false)));
        when(toss.cancel(eq(pk(orderRef)), eq(COMPENSATION_REASON), eq(key)))
                .thenReturn(compensationCanceled(orderRef));
        makeDue(refund);
        retryJob.run();

        InOrder order = inOrder(toss);
        order.verify(toss, atLeastOnce()).getPayment(pk(orderRef));
        order.verify(toss).cancel(pk(orderRef), COMPENSATION_REASON, key);
        verify(toss, times(2)).cancel(any(), any(), any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @DisplayName("[REF-LATE-02] 재개된 시스템 보상은 이미 이 보상의 취소가 토스에 있으면 재요청 없이 결과를 반영한다")
    void resumedCompensationSettlesWithoutResendWhenCancelExists() throws Exception {
        String orderRef = "RB-COMPOK-" + UUID.randomUUID().toString().substring(0, 8);
        Refund refund = stuckCompensation(orderRef);
        jdbc.update(sql("resume-stuck-refund"));

        when(toss.getPayment(pk(orderRef)))
                .thenReturn(new TossResult.Success(compensationCanceledPayment(orderRef)));
        makeDue(refund);
        retryJob.run();

        verify(toss, times(1)).cancel(any(), any(), any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
    }

    /**
     * 승인된 결제에 시스템 보상을 접수하고, 첫 취소가 불명으로 끝난 뒤 마감이 지나 멈춘 상태(UNKNOWN, next_attempt_at NULL)로 만든다. 취소
     * 사유는 보상 사유, 키는 cancel-{pg_order_id}다.
     */
    private Refund stuckCompensation(String orderRef) throws Exception {
        long paymentId = newPayment(orderRef);
        jdbc.update(
                "UPDATE payment SET status = 'APPROVED', partial_cancelable = true, pg_payment_key = ?,"
                        + " pg_order_id = ? WHERE id = ?",
                pk(orderRef),
                "PO-" + orderRef,
                paymentId);
        jdbc.update(
                "INSERT INTO pg_call (payment_id, kind, pg_order_id, outcome, requested_at, responded_at)"
                        + " VALUES (?, 'CONFIRM', ?, 'SUCCESS', now(), now())",
                paymentId,
                "PO-" + orderRef);
        ObjectNode request = request(orderRef, paymentId, "SYSTEM_COMPENSATION");
        request.put("amount", AMOUNT);
        ObjectNode item = (ObjectNode) request.get("items").get(0);
        item.put("amount", AMOUNT);
        ((ObjectNode) item.get("orderItems").get(0)).put("amount", AMOUNT);
        Refund refund = intake.receive(copy(request)).orElseThrow();

        when(toss.getPayment(pk(orderRef)))
                .thenReturn(new TossResult.Success(paymentWith(orderRef, "DONE", false)));
        when(toss.cancel(eq(pk(orderRef)), eq(COMPENSATION_REASON), eq("cancel-PO-" + orderRef)))
                .thenReturn(new TossResult.Unknown("시간 초과"));
        makeDue(refund);
        retryJob.run();
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");

        // 조회 마감이 지난 뒤 재처리: 취소가 없으니 재요청 없이 멈춘다
        jdbc.update(
                "UPDATE refund SET check_deadline_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
        makeDue(refund);
        retryJob.run();
        assertThat(refund(refund.getRefundRequestId()).get("next_attempt_at")).isNull();
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        verify(toss, times(1)).cancel(any(), any(), any());
        return refund;
    }

    private void makeDue(Refund refund) {
        jdbc.update(
                "UPDATE refund SET next_attempt_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
    }

    private static TossPayment paymentWith(String orderRef, String status, boolean withCancel) {
        return new TossPayment(pk(orderRef), orderRef, status, AMOUNT, null, true, List.of());
    }

    private static TossPayment compensationCanceledPayment(String orderRef) {
        return new TossPayment(
                pk(orderRef),
                orderRef,
                "CANCELED",
                AMOUNT,
                null,
                true,
                List.of(
                        new TossPayment.Cancel(
                                "tx-" + orderRef, COMPENSATION_REASON, AMOUNT, "DONE")));
    }

    private static TossResult compensationCanceled(String orderRef) {
        return new TossResult.Success(compensationCanceledPayment(orderRef));
    }

    /** 운영 문서의 SQL 블록. 마커 뒤 첫 sql 블록을 그대로 읽는다. */
    static String sql(String marker) throws IOException {
        String doc = Files.readString(root().resolve(RUNBOOK));
        int at = doc.indexOf("<!-- sql: " + marker + " -->");
        assertThat(at).as("runbook marker %s", marker).isPositive();
        int open = doc.indexOf("```sql\n", at);
        int close = doc.indexOf("\n```", open + 7);
        assertThat(open).as("sql block for %s", marker).isPositive();
        return doc.substring(open + 7, close);
    }

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (!Files.exists(path.resolve("docs/README.md"))) {
            path = path.getParent();
        }
        return path;
    }

    /** 결제를 승인하고 4,000원 부분 환불을 접수한 뒤, 조회 마감이 지나 멈춘 상태(UNKNOWN, next_attempt_at NULL)로 만든다. */
    private Refund stuckUserRefund(String orderRef) throws Exception {
        long paymentId = newPayment(orderRef);
        jdbc.update(
                "UPDATE payment SET status = 'APPROVED', partial_cancelable = true, pg_payment_key = ?"
                        + " WHERE id = ?",
                pk(orderRef),
                paymentId);
        ObjectNode request = request(orderRef, paymentId, "USER_REFUND");
        Refund refund = intake.receive(copy(request)).orElseThrow();

        when(toss.cancel(any(), any(), any(), any())).thenReturn(new TossResult.Unknown("시간 초과"));
        when(toss.getPayment(pk(orderRef)))
                .thenReturn(new TossResult.Success(tossPayment(orderRef, false)));
        executor.execute(refund);
        jdbc.update(
                "UPDATE refund SET check_deadline_at = now() - INTERVAL '1' SECOND,"
                        + " next_attempt_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
        executor.retryDue();
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        assertThat(refund(refund.getRefundRequestId()).get("next_attempt_at")).isNull();
        return refund;
    }

    /** 같은 스키마에 새 애플리케이션 컨텍스트를 띄운다. 프로세스 메모리는 공유하지 않는다. */
    private ConfigurableApplicationContext restart() {
        Map<String, Object> props =
                Map.<String, Object>of(
                        "spring.datasource.url",
                        url(),
                        "spring.datasource.username",
                        System.getenv("PAYMENT_TEST_POSTGRES_USER"),
                        "spring.datasource.password",
                        System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"),
                        "spring.flyway.schemas",
                        SCHEMA,
                        "spring.datasource.hikari.maximum-pool-size",
                        4,
                        "payment.core-service-token",
                        "payment-runbook-test-credential-32",
                        "spring.kafka.bootstrap-servers",
                        "localhost:1",
                        "shoppinglive.outbox.relay.enabled",
                        false,
                        "server.port",
                        0);
        // 기본 속성(properties)은 application.yml보다 낮아 명령행 인자로 넘긴다
        String[] args =
                props.entrySet().stream()
                        .map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new);
        return new SpringApplicationBuilder(
                        PaymentServiceApplication.class, RestartTossConfig.class)
                .profiles("test")
                .run(args);
    }

    @TestConfiguration
    static class RestartTossConfig {
        @Bean
        @Primary
        TossPaymentsClient restartedTossPaymentsClient() {
            return org.mockito.Mockito.mock(TossPaymentsClient.class);
        }
    }

    private static String pk(String orderRef) {
        return "pk-" + orderRef;
    }

    private static String reason(Refund refund) {
        return "고객 환불 " + refund.getRefundRequestId();
    }

    private static TossPayment tossPayment(String orderRef, boolean withCancel) {
        return new TossPayment(
                pk(orderRef),
                orderRef,
                "PARTIAL_CANCELED",
                AMOUNT,
                null,
                true,
                withCancel
                        ? List.of(new TossPayment.Cancel("tx-" + orderRef, "", 4000, "DONE"))
                        : List.of());
    }

    private static TossResult canceled(String orderRef, Refund refund) {
        return new TossResult.Success(
                new TossPayment(
                        pk(orderRef),
                        orderRef,
                        "PARTIAL_CANCELED",
                        AMOUNT,
                        null,
                        true,
                        List.of(
                                new TossPayment.Cancel(
                                        "tx-" + orderRef, reason(refund), 4000, "DONE"))));
    }
}
