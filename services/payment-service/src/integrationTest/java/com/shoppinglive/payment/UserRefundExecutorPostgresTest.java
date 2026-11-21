package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.dto.CreatePaymentRequest;
import com.shoppinglive.payment.payment.dto.SellerAmountRequest;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.service.user.UserRefundExecutor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 고객 환불 실행을 실제 PostgreSQL과 가짜 토스로 확인한다. 실제 토스는 부르지 않는다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class UserRefundExecutorPostgresTest extends RefundPostgresTestSupport {
    static final String SCHEMA =
            "payment_user_refund_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        p.add("payment.core-service-token", () -> "payment-user-refund-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired UserRefundExecutor executor;
    @MockitoBean TossPaymentsClient toss;

    private String key(String orderRef) {
        return "pk-" + orderRef;
    }

    private long approved(String orderRef, boolean partialCancelable) {
        long id = newPayment(orderRef);
        approve(id, orderRef, partialCancelable);
        return id;
    }

    private void approve(long id, String orderRef, boolean partialCancelable) {
        jdbc.update(
                "UPDATE payment SET status = 'APPROVED', partial_cancelable = ?,"
                        + " pg_payment_key = ? WHERE id = ?",
                partialCancelable,
                key(orderRef),
                id);
    }

    private Refund receive(String orderRef, long paymentId, String seller, long amount)
            throws Exception {
        ObjectNode request = request(orderRef, paymentId, "USER_REFUND");
        request.put("amount", amount);
        ObjectNode item = (ObjectNode) request.get("items").get(0);
        item.put("sellerOrderNumber", seller);
        item.put("amount", amount);
        ((ObjectNode) item.get("orderItems").get(0)).put("amount", amount);
        return intake.receive(copy(request)).orElseThrow();
    }

    private static String reason(Refund refund) {
        return "고객 환불 " + refund.getRefundRequestId();
    }

    private TossResult canceled(Refund refund) {
        return new TossResult.Success(tossPayment(refund, "PARTIAL_CANCELED", true));
    }

    private TossPayment tossPayment(Refund refund, String status, boolean withCancel) {
        return new TossPayment(
                "pk",
                "o",
                status,
                AMOUNT,
                null,
                true,
                withCancel
                        ? List.of(
                                new TossPayment.Cancel(
                                        "tx-" + refund.getId(),
                                        reason(refund),
                                        refund.getAmount(),
                                        "DONE"))
                        : List.of());
    }

    private void makeDue(Refund refund) {
        jdbc.update(
                "UPDATE refund SET next_attempt_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
    }

    private void assertNoMoneyMoved(Refund refund, long paymentId) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payment WHERE id = ?",
                                Long.class,
                                paymentId))
                .isZero();
        assertThat(refund(refund.getRefundRequestId())).containsEntry("refunded_amount", 0L);
    }

    private void assertFailed(Refund refund, String code) {
        assertThat(refund(refund.getRefundRequestId()))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_code", code)
                .containsEntry("result_version", 1);
    }

    @Test
    @DisplayName("[REF-EXEC-02] 부분 환불은 요청 금액과 멱등 키 refund_request_id로 취소하고 성공을 반영한다")
    void partialRefund() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any())).thenReturn(canceled(refund));

        executor.execute(refund);

        verify(toss).cancel(key(orderRef), reason(refund), refund.getRefundRequestId(), 4000L);
        assertThat(refund(refund.getRefundRequestId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("pg_transaction_key", "tx-" + refund.getId());
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", paymentId))
                .containsEntry("status", "PARTIALLY_REFUNDED")
                .containsEntry("refunded_amount", 4000L);
        assertThat(results(orderRef)).hasSize(1);
    }

    @Test
    @DisplayName("[REF-EXEC-02] 전액 환불은 금액 없이 취소한다. 부분 취소가 안 되는 결제도 전액은 된다")
    void fullRefund() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, false);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", AMOUNT);
        when(toss.cancel(any(), any(), any(), isNull())).thenReturn(canceled(refund));

        executor.execute(refund);

        verify(toss).cancel(key(orderRef), reason(refund), refund.getRefundRequestId(), null);
        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", paymentId))
                .containsEntry("status", "REFUNDED")
                .containsEntry("refunded_amount", AMOUNT);
    }

    @Test
    @DisplayName("[REF-ACCEPT-03] 누적 환불이 결제 금액을 넘으면 토스를 부르지 않고 FAILED(OVER_REFUND)다")
    void overRefund() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund first = receive(orderRef, paymentId, orderRef + "-S1", 20000);
        when(toss.cancel(any(), any(), eq(first.getRefundRequestId()), any()))
                .thenReturn(canceled(first));
        executor.execute(first);
        Refund second = receive(orderRef, paymentId, orderRef + "-S1", 20000);

        executor.execute(second);

        assertFailed(second, "OVER_REFUND");
        verify(toss, never()).cancel(any(), any(), eq(second.getRefundRequestId()), any());
    }

    @Test
    @DisplayName("[REF-ACCEPT-03] 합이 결제 금액을 넘는 동시 요청은 한 건만 토스로 간다")
    void concurrentOverRefund() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund a = receive(orderRef, paymentId, orderRef + "-S1", 20000);
        Refund b = receive(orderRef, paymentId, orderRef + "-S1", 20000);
        when(toss.cancel(any(), any(), anyString(), any()))
                .thenAnswer(
                        call ->
                                canceled(
                                        call.getArgument(2).equals(a.getRefundRequestId())
                                                ? a
                                                : b));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (Refund refund : List.of(a, b)) {
                pool.submit(
                        () -> {
                            start.await();
                            executor.execute(refund);
                            return null;
                        });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        verify(toss, times(1)).cancel(any(), any(), any(), any());
        assertThat(
                        List.of(
                                refund(a.getRefundRequestId()).get("status"),
                                refund(b.getRefundRequestId()).get("status")))
                .containsExactlyInAnyOrder("SUCCEEDED", "FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payment WHERE id = ?",
                                Long.class,
                                paymentId))
                .isEqualTo(20000L);
    }

    @Test
    @DisplayName("[REF-ACCEPT-03] 판매자 주문별 누적 환불이 그 주문 금액을 넘어도 OVER_REFUND다")
    void sellerOverRefund() throws Exception {
        String orderRef = newOrderRef();
        payments.create(
                new CreatePaymentRequest(
                        orderRef,
                        UUID.fromString(BUYER),
                        AMOUNT,
                        OffsetDateTime.now().plusMinutes(10),
                        List.of(
                                new SellerAmountRequest(
                                        orderRef + "-S1",
                                        UUID.randomUUID(),
                                        10000L,
                                        new BigDecimal("0.1000")),
                                new SellerAmountRequest(
                                        orderRef + "-S2",
                                        UUID.randomUUID(),
                                        20000L,
                                        new BigDecimal("0.1000")))));
        long paymentId =
                jdbc.queryForObject(
                        "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
        approve(paymentId, orderRef, true);
        Refund first = receive(orderRef, paymentId, orderRef + "-S1", 8000);
        when(toss.cancel(any(), any(), eq(first.getRefundRequestId()), any()))
                .thenReturn(canceled(first));
        executor.execute(first);
        Refund second = receive(orderRef, paymentId, orderRef + "-S1", 8000);

        executor.execute(second);

        assertFailed(second, "OVER_REFUND");
    }

    @Test
    @DisplayName("[REF-ACCEPT-04] 부분 취소 불가·없는 판매자 주문·환불 불가 상태는 돈을 움직이지 않고 FAILED다")
    void rejectsBeforeToss() throws Exception {
        String partialRef = newOrderRef();
        long partialPayment = approved(partialRef, false);
        Refund partial = receive(partialRef, partialPayment, partialRef + "-S1", 4000);
        executor.execute(partial);
        assertFailed(partial, "PARTIAL_NOT_ALLOWED");
        assertNoMoneyMoved(partial, partialPayment);

        String sellerRef = newOrderRef();
        long sellerPayment = approved(sellerRef, true);
        Refund seller = receive(sellerRef, sellerPayment, "other-seller-order", 4000);
        executor.execute(seller);
        assertFailed(seller, "UNKNOWN_SELLER_ORDER");
        assertNoMoneyMoved(seller, sellerPayment);

        String createdRef = newOrderRef();
        long createdPayment = newPayment(createdRef);
        Refund created = receive(createdRef, createdPayment, createdRef + "-S1", 4000);
        executor.execute(created);
        assertFailed(created, "NOT_REFUNDABLE");
        assertNoMoneyMoved(created, createdPayment);

        verify(toss, never()).cancel(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[REF-EXEC-03] 토스가 거절하면 FAILED와 환불액 0 결과를 남긴다")
    void declined() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any()))
                .thenReturn(new TossResult.Declined("NOT_CANCELABLE_AMOUNT", "거절", 400));

        executor.execute(refund);

        assertFailed(refund, "NOT_CANCELABLE_AMOUNT");
        assertNoMoneyMoved(refund, paymentId);
        assertThat(results(orderRef).get(0).get("payload").get("refundedAmount").longValue())
                .isZero();
    }

    @Test
    @DisplayName("[REF-EXEC-04] 결과 불명은 UNKNOWN으로 두고 결과를 보내지 않는다. 조회에 취소가 있으면 재요청 없이 성공 반영한다")
    void unknownThenFoundByQuery() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any())).thenReturn(new TossResult.Unknown("시간 초과"));

        executor.execute(refund);

        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        assertThat(refund(refund.getRefundRequestId()).get("check_deadline_at")).isNotNull();
        assertThat(results(orderRef)).isEmpty();

        when(toss.getPayment(key(orderRef))).thenReturn(canceled(refund));
        makeDue(refund);
        executor.retryDue();

        verify(toss, times(1)).cancel(any(), any(), any(), any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
        assertThat(results(orderRef)).hasSize(1);
    }

    @Test
    @DisplayName("[REF-EXEC-04] 조회에 취소 내역이 없으면 같은 멱등 키·본문으로 다시 요청한다")
    void unknownThenResendsSameKey() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any()))
                .thenReturn(new TossResult.Unknown("시간 초과"), canceled(refund));
        when(toss.getPayment(key(orderRef)))
                .thenReturn(new TossResult.Success(tossPayment(refund, "DONE", false)));

        executor.execute(refund);
        makeDue(refund);
        executor.retryDue();

        verify(toss, times(2))
                .cancel(key(orderRef), reason(refund), refund.getRefundRequestId(), 4000L);
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
    }

    @Test
    @DisplayName("[REF-EXEC-04] 결과 불명 뒤 재요청이 거절돼도 앞선 취소가 성공했을 수 있어 FAILED로 단정하지 않는다")
    void declinedAfterUnknownStaysUnknown() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any()))
                .thenReturn(
                        new TossResult.Unknown("시간 초과"),
                        new TossResult.Declined("ALREADY_CANCELED_PAYMENT", "이미 취소", 400));
        when(toss.getPayment(key(orderRef)))
                .thenReturn(new TossResult.Success(tossPayment(refund, "DONE", false)));

        executor.execute(refund);
        makeDue(refund);
        executor.retryDue();

        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        assertThat(results(orderRef)).isEmpty();
    }

    @Test
    @DisplayName("[REF-EXEC-04] 조회 마감이 지나면 재요청 없이 멈추고 UNKNOWN을 유지한다")
    void stopsAfterDeadline() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any())).thenReturn(new TossResult.Unknown("시간 초과"));
        when(toss.getPayment(key(orderRef)))
                .thenReturn(new TossResult.Success(tossPayment(refund, "DONE", false)));
        executor.execute(refund);
        jdbc.update(
                "UPDATE refund SET check_deadline_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
        makeDue(refund);

        executor.retryDue();

        verify(toss, times(1)).cancel(any(), any(), any(), any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "UNKNOWN");
        assertThat(refund(refund.getRefundRequestId()).get("next_attempt_at")).isNull();
        assertThat(results(orderRef)).isEmpty();
    }

    @Test
    @DisplayName("[REF-EXEC-01] 커밋 뒤 실행되지 못한 환불은 재처리가 집고, 선점된 환불은 다시 실행하지 않으며, 시스템 보상은 집지 않는다")
    void retriesOnlyDueUserRefunds() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        String otherRef = newOrderRef();
        Refund compensation =
                intake.receive(request(otherRef, approved(otherRef, true), "SYSTEM_COMPENSATION"))
                        .orElseThrow();
        makeDue(refund);
        makeDue(compensation);
        when(toss.cancel(any(), any(), any(), any())).thenReturn(canceled(refund));

        executor.retryDue();
        executor.execute(refund);

        verify(toss, times(1)).cancel(any(), any(), any(), any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
        assertThat(refund(compensation.getRefundRequestId()))
                .containsEntry("status", "PROCESSING")
                .containsEntry("attempt_count", 0);
    }

    @Test
    @DisplayName("[REF-EXEC-01] 첫 호출이 토스에 가지 않았으면 PROCESSING으로 돌려 다음 실행이 다시 검증한다")
    void notSentReturnsToProcessing() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approved(orderRef, true);
        Refund refund = receive(orderRef, paymentId, orderRef + "-S1", 4000);
        when(toss.cancel(any(), any(), any(), any()))
                .thenReturn(new TossResult.NotSent("연결 실패"), canceled(refund));

        executor.execute(refund);

        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "PROCESSING");
        assertThat(refund(refund.getRefundRequestId()).get("check_deadline_at")).isNull();

        makeDue(refund);
        executor.retryDue();

        verify(toss, never()).getPayment(any());
        assertThat(refund(refund.getRefundRequestId())).containsEntry("status", "SUCCEEDED");
    }
}
