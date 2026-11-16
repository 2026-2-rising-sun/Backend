package com.shoppinglive.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.PaymentServiceApplication;
import com.shoppinglive.payment.dto.*;
import com.shoppinglive.payment.entity.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=test",
            "payment.dev.payment-scenario.enabled=true",
            "shoppinglive.outbox.relay.enabled=false",
            "payment.execution.recovery-enabled=false",
            "payment.refunds.execution-enabled=false"
        })
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentBoundaryPostgresTest {
    static final String TOKEN = "payment-boundary-postgres-test-credential-32";
    static final String SCHEMA = "payment_http_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.flyway.locations", () -> "classpath:db/payment-migration");
        p.add("payment.core-service-token", () -> TOKEN);
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired RefundIntakeService refunds;
    @Autowired RefundExecutionService executions;
    @Autowired RefundRecoveryStore refundRecovery;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @MockitoBean MockPaymentEngine engine;
    @MockitoBean RefundMockEngine refundEngine;
    @MockitoSpyBean DurableMockGateway gateway;
    @MockitoSpyBean DurableMockRefundGateway refundGateway;
    @MockitoSpyBean EventOutbox outbox;
    RestClient http;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM mock_refund_result");
        jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM mock_gateway_result");
        jdbc.update("DELETE FROM payment_attempt");
        http =
                RestClient.builder()
                        .baseUrl("http://localhost:" + port)
                        .defaultHeader("X-Service-Token", TOKEN)
                        .build();
    }

    PaymentCommand command(long id, long amount, PaymentScenario scenario) {
        return new PaymentCommand(
                UUID.randomUUID().toString(), id, 1L, 1L, "PG-1", amount, scenario);
    }

    PaymentExecution post(PaymentCommand c) {
        return http.post()
                .uri("/v1/internal/payments")
                .body(c)
                .retrieve()
                .body(PaymentExecution.class);
    }

    PaymentExecution paid(long amount) {
        var c = command(1, amount, PaymentScenario.INSTANT_SUCCESS);
        var p = post(c);
        if (amount > 0) assertThat(payments.resolvePayment(p.paymentId())).isTrue();
        return payments.get(p.paymentId());
    }

    RefundCommand refund(long id, long amount) {
        return new RefundCommand(UUID.randomUUID().toString(), id, 1L, 1L, 1L, "PG-1", amount);
    }

    void duePayment() {
        jdbc.update(
                "UPDATE payment_attempt SET scheduled_resolve_at=clock_timestamp()-INTERVAL '1 second'");
    }

    void dueRefund() {
        jdbc.update(
                "UPDATE refund_execution SET next_action_at=clock_timestamp()-INTERVAL '1 second'");
    }

    @Test
    @DisplayName("토큰 없는 probe는 열고 결제 내부 API는 서비스 토큰으로 보호한다")
    void healthAndServiceTokenBoundary() {
        var anonymous = RestClient.create("http://localhost:" + port);
        assertThat(
                        anonymous
                                .get()
                                .uri("/actuator/health/liveness")
                                .retrieve()
                                .toBodilessEntity()
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThatThrownBy(
                        () ->
                                anonymous
                                        .get()
                                        .uri("/v1/internal/payments/1")
                                        .retrieve()
                                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
        assertThatThrownBy(
                        () ->
                                anonymous
                                        .get()
                                        .uri("/v1/internal/payments/1")
                                        .header("X-Service-Token", "wrong")
                                        .retrieve()
                                        .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    @Test
    @DisplayName("0원 POST 재전달은 PostgreSQL 원본 시각과 성공 이벤트 하나를 재사용한다")
    void zeroPaymentCanonicalReplay() {
        var c = command(1, 0, null);
        var first = post(c);
        var replay = post(c);
        assertThat(first).isEqualTo(replay).isEqualTo(payments.get(1));
        assertThat(first.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result", Long.class))
                .isZero();
        verify(gateway, never()).authorize(anyLong(), any());
        var changed =
                new PaymentCommand(
                        c.requestId(),
                        c.paymentId(),
                        c.orderId(),
                        c.paymentGroupId(),
                        c.referenceNumber(),
                        1,
                        null);
        assertThatThrownBy(() -> post(changed))
                .isInstanceOf(HttpClientErrorException.Conflict.class);
    }

    @Test
    @DisplayName("같은 requestId 동시 HTTP 접수는 Payment 원본 하나만 생성한다")
    void concurrentSameRequestHasOneOriginal() throws Exception {
        var c = command(1, 1000, null);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var fs = new ArrayList<Future<PaymentExecution>>();
            for (int i = 0; i < 4; i++)
                fs.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return post(c);
                                }));
            go.countDown();
            for (var f : fs) assertThat(f.get(15, TimeUnit.SECONDS).paymentId()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PG 승인 뒤 결과 Outbox 롤백은 독립 승인 원본을 보존하고 재실행 없이 복구한다")
    void approvalSurvivesResultTransactionRollback() {
        var p = post(command(1, 1000, PaymentScenario.INSTANT_SUCCESS));
        doThrow(new IllegalStateException("Outbox 실패"))
                .doCallRealMethod()
                .when(
                        (EventOutbox)
                                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                        outbox))
                .append(eq(ExecutionResultPublisher.PAYMENTS), any());
        assertThatThrownBy(() -> payments.resolvePayment(p.paymentId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isZero();
        duePayment();
        assertThat(payments.resolvePayment(1L)).isTrue();
        verify(gateway, times(1)).authorize(1L, PaymentScenario.INSTANT_SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("결제 재시도 상한은 UNKNOWN을 유지하며 이후 확인된 결과를 조회로 확정한다")
    void paymentRetryExhaustionRemainsUnknown() {
        post(command(1, 1000, PaymentScenario.UNAVAILABLE_BEFORE_RESULT));
        for (int i = 0; i < 6; i++) {
            duePayment();
            assertThat(payments.resolvePayment(1L)).isFalse();
        }
        assertThat(payments.get(1).status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt", Integer.class))
                .isEqualTo(3);
        verify(gateway, times(4)).authorize(1L, PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isZero();
        gateway.authorize(1L, PaymentScenario.INSTANT_SUCCESS);
        duePayment();
        assertThat(payments.resolvePayment(1L)).isTrue();
        assertThat(jdbc.queryForObject("SELECT retry_count FROM payment_attempt", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("부분 환불 뒤 잔액 환불은 허용하고 누적 한도 초과와 변경된 요청은 거절한다")
    void refundCumulativeLimitAndConflict() {
        paid(1000);
        var a = refund(1, 400);
        var first = refunds.start(a);
        assertThat(refunds.start(a)).isEqualTo(first);
        assertThat(executions.execute(1)).isTrue();
        var b = refund(2, 600);
        refunds.start(b);
        assertThat(executions.execute(2)).isTrue();
        assertThatThrownBy(() -> refunds.start(refund(3, 1))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                refunds.start(
                                        new RefundCommand(
                                                a.requestId(), 1L, 1L, 1L, 1L, "PG-1", 399)))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(refund_amount) FROM refund_execution WHERE status='SUCCESS'",
                                Long.class))
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("0원 환불은 Payment 원본과 결과를 기록하되 PG를 호출하지 않는다")
    void zeroRefundDoesNotCallGateway() {
        paid(0);
        refunds.start(refund(1, 0));
        assertThat(executions.execute(1)).isTrue();
        assertThat(refunds.get(1).status()).isEqualTo(RefundStatus.SUCCESS);
        verifyNoInteractions(refundGateway);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='RefundExecutionResolved'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("환불 상한 소진 후에도 UNKNOWN을 유지하고 저장된 PG 결과는 재호출 없이 복구한다")
    void refundExhaustionRemainsUnknownAndKnownOutcomeIsReusable() {
        paid(1000);
        refunds.start(refund(1, 1000));
        for (int i = 0; i < 6; i++) {
            dueRefund();
            assertThat(executions.execute(1, MockRefundScenario.UNKNOWN_BEFORE_RESULT)).isFalse();
        }
        assertThat(refunds.get(1).status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(refunds.get(1).retryCount()).isEqualTo(3);
        verify(refundGateway, times(4)).execute(1, 1000, MockRefundScenario.UNKNOWN_BEFORE_RESULT);
        refundGateway.execute(1, 1000, MockRefundScenario.SUCCESS);
        dueRefund();
        assertThat(executions.execute(1)).isTrue();
        assertThat(refunds.get(1).retryCount()).isEqualTo(3);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 1000})
    @DisplayName("Payment 실행 비활성 HTTP 접수는 예약하지 않고 재활성 worker가 기존 요청을 복구한다")
    void disabledPaymentKeepsRequestsForReactivatedWorker(long paidAmount) {
        paid(paidAmount);
        var command = refund(1, paidAmount / 2);
        var pending =
                http.post()
                        .uri("/v1/internal/refunds")
                        .body(command)
                        .retrieve()
                        .body(RefundExecution.class);
        assertThat(pending.status()).isEqualTo(RefundStatus.PROCESSING);
        verify(refundEngine, never()).schedule(anyLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result", Long.class))
                .isZero();
        var enabledIntake = new RefundIntakeService(jdbc, transaction, refundEngine, true);
        enabledIntake.start(refund(2, paidAmount - paidAmount / 2));
        verify(refundEngine, times(1)).schedule(2);
        assertThat(new RefundReconciler(refundRecovery, executions).reconcileDue()).isEqualTo(2);
        assertThat(refunds.get(1).requestId()).isEqualTo(command.requestId());
        assertThat(refunds.get(1).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(refunds.get(2).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result", Long.class))
                .isEqualTo(paidAmount == 0 ? 0 : 2);
    }

    @Test
    @DisplayName("JVM 시각이 DB보다 앞서도 0원 환불 접수는 즉시 선점하고 결과 시각을 보존한다")
    void initialScheduleDoesNotFollowAnAheadJvmClock() {
        paid(0);
        Instant future = Instant.now().plusSeconds(300);
        try (var clock =
                org.mockito.Mockito.mockStatic(
                        Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(future);
            refunds.start(refund(1, 0));
            assertThat(executions.execute(1)).isTrue();
            assertThat(refunds.get(1).requestedAt()).isBefore(future);
            assertThat(refunds.get(1).resolvedAt()).isAfterOrEqualTo(refunds.get(1).requestedAt());
        }
    }

    @Test
    @DisplayName("DB 시각으로 접수한 0원 환불은 대기 없이 연속 선점할 수 있다")
    void immediatelyClaimsZeroRefundsUsingDatabaseTime() {
        paid(0);
        for (long id = 1; id <= 20; id++) {
            refunds.start(refund(id, 0));
            assertThat(executions.execute(id)).as("접수 직후 선점 id=%s", id).isTrue();
        }
        verifyNoInteractions(refundGateway);
    }

    @Test
    @DisplayName("환불 결과 Outbox 롤백은 PG 원본을 보존하고 같은 결과를 복구한다")
    void refundResultOutboxRollbackPreservesGatewayOriginal() {
        paid(1000);
        refunds.start(refund(1, 1000));
        doThrow(new IllegalStateException("환불 Outbox 실패"))
                .doCallRealMethod()
                .when(
                        (EventOutbox)
                                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                        outbox))
                .append(eq(ExecutionResultPublisher.REFUNDS), any());
        assertThatThrownBy(() -> executions.execute(1)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result", Long.class))
                .isEqualTo(1);
        dueRefund();
        assertThat(executions.execute(1)).isTrue();
        verify(refundGateway, times(1)).execute(1, 1000, MockRefundScenario.SUCCESS);
    }
}
