package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.config.PaymentError;
import com.shoppinglive.payment.config.PaymentException;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import com.shoppinglive.payment.payment.service.PaymentOutcomeService;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.service.RefundIntakeService;
import com.shoppinglive.payment.refund.service.compensation.CompensationRetryJob;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 시스템 보상(#386): 결제 상태별 처리와 토스 조회 결과별 처리. 토스는 테스트 대역이고 시각은 DB 값을 과거로 넣어 만든다. */
@ExtendWith(OutputCaptureExtension.class)
class CompensationPostgresTest extends RecoveryPostgresTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TossResult NOT_FOUND =
            new TossResult.Declined("NOT_FOUND_PAYMENT", "없는 결제", 404);
    private static final TossResult ALREADY_CANCELED =
            new TossResult.Declined("ALREADY_CANCELED_PAYMENT", "이미 취소", 400);

    @Autowired RefundIntakeService intake;
    @Autowired CompensationRetryJob retryJob;
    @Autowired ApprovalService approvalService;
    @Autowired TransactionTemplate tx;

    /** 같은 스키마의 다른 테스트가 남긴 보상이 재처리 대상이 되지 않게 한다. */
    @BeforeEach
    void isolateOtherRefunds() {
        jdbc.update("UPDATE refund SET next_attempt_at = NULL");
    }

    @Test
    @DisplayName("[REF-LATE-02] CREATED는 막고, 이전 시도의 paymentKey가 남아 있어도 조회 없이 NO_CHARGE")
    void createdIsNoCharge() throws Exception {
        ApproveCommand c = approving();
        jdbc.update(
                "UPDATE payment SET status = 'CREATED', pg_order_id = NULL WHERE order_ref = ?",
                c.orderRef());

        Refund refund = receive(c);
        intake.execute(refund);

        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertResolved(c, refund, "NO_CHARGE", 0);
        verify(toss, never()).getPayment(c.paymentKey());
    }

    @Test
    @DisplayName("[REF-LATE-02] CANCELED이고 paymentKey가 없으면 조회 없이 NO_CHARGE")
    void canceledWithoutKeyIsNoCharge() throws Exception {
        ApproveCommand c = newPayment();
        jdbc.update("UPDATE payment SET status = 'CANCELED' WHERE order_ref = ?", c.orderRef());

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "NO_CHARGE", 0);
        verify(toss, never()).getPayment(anyString());
    }

    @Test
    @DisplayName(
            "[REF-LATE-01] CANCELED에 paymentKey가 있으면 조회하고, 결제창이 비어 있어도 그 시도의 키로 취소해 SUCCEEDED. 분개 없음")
    void canceledWithKeyQueriesAndCancelsWithAttemptKey() throws Exception {
        ApproveCommand c = approving();
        jdbc.update(
                "UPDATE payment SET status = 'CANCELED', pg_order_id = NULL WHERE order_ref = ?",
                c.orderRef());
        tossQuery(c, new TossResult.Success(done(c)));
        tossCancel(c, new TossResult.Success(withStatus(c, "CANCELED")));

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        verify(toss).cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId()));
        assertThat(journals(c.orderRef())).isZero();
    }

    @Test
    @DisplayName(
            "[REF-LATE-01] APPROVING은 막은 뒤 트랜잭션 밖에서 조회하고 DONE이면 기존 보상과 같은 키로 취소해 SUCCEEDED. 분개 없음")
    void approvingDoneCancels() throws Exception {
        ApproveCommand c = approving();
        when(toss.getPayment(c.paymentKey()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .as("토스 조회 중 트랜잭션")
                                    .isFalse();
                            assertThat(status(c.orderRef())).isEqualTo("CANCELED");
                            return new TossResult.Success(done(c));
                        });
        tossCancel(c, new TossResult.Success(withStatus(c, "CANCELED")));

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        assertThat(journals(c.orderRef())).isZero();
    }

    @Test
    @DisplayName("[REF-LATE-01] 조회 마감이 지나 조회기가 멈춘 UNKNOWN도 보상이 직접 조회해 확정한다")
    void unknownPastCheckDeadlineResolves() throws Exception {
        ApproveCommand c = unknown(Duration.ofHours(1));
        jdbc.update("UPDATE payment SET next_check_at = NULL WHERE order_ref = ?", c.orderRef());
        tossQuery(c, new TossResult.Success(withStatus(c, "CANCELED")));

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "EXPIRED"})
    @DisplayName("[REF-LATE-02] 조회가 ABORTED·EXPIRED면 NO_CHARGE")
    void abortedOrExpiredIsNoCharge(String pgStatus) throws Exception {
        ApproveCommand c = approving();
        tossQuery(c, new TossResult.Success(withStatus(c, pgStatus)));

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "NO_CHARGE", 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "READY", "NOT_FOUND_PAYMENT"})
    @DisplayName(
            "[REF-LATE-02] 진행 중·404는 첫 CONFIRM + 2×응답 한도 + 여유 전에는 보류하고, 지난 뒤 다시 조회해 그대로면 NO_CHARGE")
    void waitsForConfirmWindowThenNoCharge(String answer) throws Exception {
        ApproveCommand c = approving();
        TossResult result =
                answer.equals("NOT_FOUND_PAYMENT")
                        ? NOT_FOUND
                        : new TossResult.Success(withStatus(c, answer));
        tossQuery(c, result);

        Refund refund = receive(c);
        intake.execute(refund);

        assertHeld(c, refund);
        assertThat(secondsToNextAttempt(refund)).isPositive();

        jdbc.update(
                "UPDATE pg_call SET requested_at = now() - INTERVAL '1' HOUR WHERE payment_id = ?",
                paymentId(c.orderRef()));
        makeDue(refund);
        retryJob.run();

        assertResolved(c, refund, "NO_CHARGE", 0);
        verify(toss, times(2)).getPayment(c.paymentKey());
    }

    @Test
    @DisplayName("[REF-LATE-02] 조회 5xx·타임아웃은 대기 시간이 지나도 NO_CHARGE로 단정하지 않고 보류한다")
    void unknownQueryNeverBecomesNoCharge() throws Exception {
        ApproveCommand c = approving();
        jdbc.update(
                "UPDATE pg_call SET requested_at = now() - INTERVAL '1' HOUR WHERE payment_id = ?",
                paymentId(c.orderRef()));
        when(toss.getPayment(c.paymentKey()))
                .thenReturn(new TossResult.Unknown("HTTP 500", 500))
                .thenReturn(new TossResult.Unknown("응답 데드라인 초과"));

        Refund refund = receive(c);
        intake.execute(refund);
        assertHeld(c, refund);

        makeDue(refund);
        retryJob.run();
        assertHeld(c, refund);
        verify(toss, times(2)).getPayment(c.paymentKey());
    }

    @Test
    @DisplayName("[REF-LATE-01] 조회가 PARTIAL_CANCELED면 취소하지 않고 결과를 보류하며 사람 확인 로그를 남긴다")
    void partialCanceledNeedsHuman(CapturedOutput output) throws Exception {
        ApproveCommand c = approving();
        tossQuery(c, new TossResult.Success(withStatus(c, "PARTIAL_CANCELED")));

        Refund refund = receive(c);
        intake.execute(refund);

        assertHeld(c, refund);
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
        assertThat(output.getAll()).contains("[사람 확인 필요]", refund.getRefundRequestId());
    }

    @Test
    @DisplayName("[REF-EXEC-04] 보상 취소가 불명·409면 간격을 늘려 같은 키로 재시도하고, 성공하면 SUCCEEDED 한 번")
    void cancelRetriesWithSameKey() throws Exception {
        ApproveCommand c = approving();
        tossQuery(c, new TossResult.Success(done(c)));
        when(toss.cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId())))
                .thenReturn(new TossResult.Unknown("HTTP 409 IDEMPOTENT_REQUEST_PROCESSING", 409))
                .thenReturn(new TossResult.Unknown("HTTP 500", 500))
                .thenReturn(new TossResult.Success(withStatus(c, "CANCELED")));

        Refund refund = receive(c);
        intake.execute(refund);
        assertHeld(c, refund);
        double first = secondsToNextAttempt(refund);

        makeDue(refund);
        retryJob.run();
        assertHeld(c, refund);
        assertThat(secondsToNextAttempt(refund)).isGreaterThan(first);

        makeDue(refund);
        retryJob.run();

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        verify(toss, times(3))
                .cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId()));
    }

    @Test
    @DisplayName("[REF-LATE-01] 취소 응답 ALREADY_CANCELED_PAYMENT는 응답 code로 구분해 SUCCEEDED")
    void alreadyCanceledSucceeds() throws Exception {
        ApproveCommand c = approving();
        tossQuery(c, new TossResult.Success(done(c)));
        tossCancel(c, ALREADY_CANCELED);

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
    }

    @Test
    @DisplayName("[REF-LATE-01] 취소 응답 NOT_FOUND_PAYMENT는 성공으로 보지 않고 보류한다")
    void cancelNotFoundIsHeld() throws Exception {
        ApproveCommand c = approving();
        tossQuery(c, new TossResult.Success(done(c)));
        tossCancel(c, NOT_FOUND);

        Refund refund = receive(c);
        intake.execute(refund);

        assertHeld(c, refund);
        verify(toss, times(1))
                .cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId()));
    }

    @Test
    @DisplayName(
            "[REF-LATE-01] confirm 진행 중에 막고 토스가 DONE이면 기존 compensate와 같은 키·같은 사유로 수렴하고 SUCCEEDED는 한 번")
    void convergesWithInFlightConfirm() throws Exception {
        ApproveCommand c = newPayment();
        ApprovalService.Attempt attempt = (ApprovalService.Attempt) approvalService.start(c);
        tossQuery(c, new TossResult.Success(done(c)));
        tossCancel(c, new TossResult.Success(withStatus(c, "CANCELED")));
        when(toss.confirm(eq(c.paymentKey()), eq(c.pgOrderId()), eq(c.amount()), anyString()))
                .thenReturn(new TossResult.Success(done(c)));

        Refund refund = receive(c);
        intake.execute(refund);
        ApprovalResult late = approvalService.process(attempt);

        assertThat(late).isInstanceOf(ApprovalResult.Closed.class);
        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        var reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(toss, times(2))
                .cancel(eq(c.paymentKey()), reason.capture(), eq("cancel-" + c.pgOrderId()));
        assertThat(reason.getAllValues().get(0)).isEqualTo(reason.getAllValues().get(1));
        assertThat(journals(c.orderRef())).isZero();
    }

    @Test
    @DisplayName("[REF-LATE-01] 경합에서 기존 compensate가 409를 받아도 사람 확인 로그 없이 SUCCEEDED 한 번으로 끝난다")
    void compensateConflictIsNotHumanCheck(CapturedOutput output) throws Exception {
        ApproveCommand c = newPayment();
        ApprovalService.Attempt attempt = (ApprovalService.Attempt) approvalService.start(c);
        tossQuery(c, new TossResult.Success(done(c)));
        when(toss.cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId())))
                .thenReturn(new TossResult.Success(withStatus(c, "CANCELED")))
                .thenReturn(new TossResult.Unknown("HTTP 409 IDEMPOTENT_REQUEST_PROCESSING", 409));
        when(toss.confirm(eq(c.paymentKey()), eq(c.pgOrderId()), eq(c.amount()), anyString()))
                .thenReturn(new TossResult.Success(done(c)));

        Refund refund = receive(c);
        intake.execute(refund);
        approvalService.process(attempt);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        assertThat(output.getAll()).doesNotContain("보상 취소가 확정되지 않았다");
    }

    @Test
    @DisplayName("[REF-LATE-01] 보상 중에 늦게 온 승인 요청은 PAYMENT_CLOSED로 거절된다")
    void lateApprovalIsClosed() throws Exception {
        ApproveCommand c = newPayment();
        Refund refund = receive(c);
        intake.execute(refund);

        assertThatThrownBy(() -> approvalService.start(c))
                .isInstanceOfSatisfying(
                        PaymentException.class,
                        e -> assertThat(e.error()).isEqualTo(PaymentError.PAYMENT_CLOSED));
    }

    @Test
    @DisplayName("[REF-EXEC-01] 재처리는 끝나지 못한 시스템 보상만 집고, 선점 중인 행은 다시 집지 않는다")
    void retryJobClaimsDueCompensationOnce() throws Exception {
        ApproveCommand c = newPayment();
        Refund refund = receive(c); // 실행 전에 죽은 접수

        retryJob.run();
        assertResolved(c, refund, "NO_CHARGE", 0);
        assertThat(status(c.orderRef())).isEqualTo("CANCELED");

        clearInvocations(toss);
        retryJob.run();
        verify(toss, never()).getPayment(any());
        assertThat(refundResults(c)).hasSize(1);
    }

    @Test
    @DisplayName(
            "[REF-LATE-01][REF-EXEC-02] APPROVED는 cancel-{pgOrderId}로 전액 취소하고 REFUNDED·refund_item·반대 분개·SUCCEEDED를 함께 남긴다")
    void approvedRefundsInFull() throws Exception {
        ApproveCommand c = approved();
        tossCancel(
                c,
                new TossResult.Success(canceledBy(c, PaymentOutcomeService.COMPENSATION_REASON)));

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        assertRefunded(c, refund);
    }

    @Test
    @DisplayName("[REF-LATE-01] APPROVED 취소 응답을 잃고 ALREADY_CANCELED면 조회한 내 취소 내역의 거래 키로 반영한다")
    void approvedAlreadyCanceledByUsSettles() throws Exception {
        ApproveCommand c = approved();
        tossCancel(c, ALREADY_CANCELED);
        tossQuery(
                c,
                new TossResult.Success(canceledBy(c, PaymentOutcomeService.COMPENSATION_REASON)));

        Refund refund = receive(c);
        intake.execute(refund);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        assertRefunded(c, refund);
    }

    @Test
    @DisplayName("[REF-LATE-01] APPROVED의 ALREADY_CANCELED가 다른 환불의 취소면 그 돈을 이 요청의 환불로 반영하지 않고 보류한다")
    void approvedCanceledByOtherRefundIsHeld() throws Exception {
        ApproveCommand c = approved();
        tossCancel(c, ALREADY_CANCELED);
        tossQuery(c, new TossResult.Success(canceledBy(c, "고객 환불 다른-요청")));

        Refund refund = receive(c);
        intake.execute(refund);

        assertHeld(c, refund);
        verify(toss).getPayment(c.paymentKey());
        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(refundJournals(refund)).isZero();
    }

    @Test
    @DisplayName("[REF-LATE-01] APPROVED 취소가 불명이면 보류하고 같은 키로 다시 보내 한 번만 반영한다")
    void approvedCancelRetries() throws Exception {
        ApproveCommand c = approved();
        when(toss.cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId())))
                .thenReturn(new TossResult.Unknown("HTTP 500", 500))
                .thenReturn(
                        new TossResult.Success(
                                canceledBy(c, PaymentOutcomeService.COMPENSATION_REASON)));

        Refund refund = receive(c);
        intake.execute(refund);
        assertHeld(c, refund);

        makeDue(refund);
        retryJob.run();

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        assertRefunded(c, refund);
    }

    @Test
    @DisplayName("[REF-LATE-01] 막으려는 UPDATE가 그 사이 APPROVED에 져서 0행이면 다시 읽어 전액 환불한다")
    void blockLosesToApproval() throws Exception {
        ApproveCommand c = newPayment();
        ApprovalService.Attempt attempt = (ApprovalService.Attempt) approvalService.start(c);
        tossCancel(
                c,
                new TossResult.Success(canceledBy(c, PaymentOutcomeService.COMPENSATION_REASON)));
        Refund refund = receive(c);

        CompletableFuture<Void> execution =
                tx.execute(
                        s -> {
                            jdbc.queryForObject(
                                    "SELECT id FROM payment WHERE order_ref = ? FOR UPDATE",
                                    Long.class,
                                    c.orderRef());
                            CompletableFuture<Void> running =
                                    CompletableFuture.runAsync(() -> intake.execute(refund));
                            awaitBlockedPaymentUpdate();
                            outcomes.applyDone(attempt.paymentId(), attempt.pgCallId(), done(c));
                            return running;
                        });
        execution.get(10, TimeUnit.SECONDS);

        assertResolved(c, refund, "SUCCEEDED", AMOUNT);
        assertRefunded(c, refund);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARTIALLY_REFUNDED", "REFUNDED"})
    @DisplayName("[REF-LATE-02] 이미 환불이 반영된 결제는 토스를 부르지 않고 FAILED(NOT_REFUNDABLE)와 사람 확인 로그")
    void alreadyRefundedFails(String paymentStatus, CapturedOutput output) throws Exception {
        ApproveCommand c = approved();
        jdbc.update(
                "UPDATE payment SET status = ?, refunded_amount = ? WHERE order_ref = ?",
                paymentStatus,
                paymentStatus.equals("REFUNDED") ? AMOUNT : 1000,
                c.orderRef());

        Refund refund = receive(c);
        intake.execute(refund);

        assertFailed(c, refund, "NOT_REFUNDABLE");
        assertThat(status(c.orderRef())).isEqualTo(paymentStatus);
        verify(toss, never()).getPayment(anyString());
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
        assertThat(output.getAll()).contains("[사람 확인 필요]", refund.getRefundRequestId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "APPROVING", "APPROVED"})
    @DisplayName("[REF-LATE-02] 요청 금액이 결제 금액과 다르면 상태와 무관하게 결제를 바꾸지 않고 FAILED(PAYMENT_MISMATCH)")
    void amountMismatchFails(String paymentStatus, CapturedOutput output) throws Exception {
        ApproveCommand c =
                switch (paymentStatus) {
                    case "APPROVING" -> approving();
                    case "APPROVED" -> approved();
                    default -> newPayment();
                };

        Refund refund = receive(c, AMOUNT - 1);
        intake.execute(refund);

        assertFailed(c, refund, "PAYMENT_MISMATCH");
        assertThat(status(c.orderRef())).isEqualTo(paymentStatus);
        verify(toss, never()).getPayment(c.paymentKey());
        verify(toss, never()).cancel(eq(c.paymentKey()), anyString(), anyString());
        assertThat(output.getAll()).contains("[사람 확인 필요]", refund.getRefundRequestId());
    }

    @Test
    @DisplayName("[REF-LATE-02] 이 보상의 취소가 토스에 닿았을 수 있으면 환불된 결제여도 환불액 0의 FAILED로 답하지 않고 보류한다")
    void ownCancelMayHaveLandedIsNotFailed(CapturedOutput output) throws Exception {
        ApproveCommand c = approved();
        jdbc.update(
                "INSERT INTO pg_call (payment_id, kind, pg_order_id, idempotency_key, outcome, requested_at)"
                        + " VALUES (?, 'CANCEL', ?, ?, 'UNKNOWN', now())",
                paymentId(c.orderRef()),
                c.pgOrderId(),
                "cancel-" + c.pgOrderId());
        jdbc.update(
                "UPDATE payment SET status = 'REFUNDED', refunded_amount = ? WHERE order_ref = ?",
                AMOUNT,
                c.orderRef());

        Refund refund = receive(c);
        intake.execute(refund);

        assertHeld(c, refund);
        assertThat(output.getAll()).contains("[사람 확인 필요]", refund.getRefundRequestId());
    }

    /** 실제 승인 흐름으로 APPROVED·승인 분개까지 만든다. */
    private ApproveCommand approved() {
        ApproveCommand c = newPayment();
        when(toss.confirm(eq(c.paymentKey()), eq(c.pgOrderId()), eq(c.amount()), anyString()))
                .thenReturn(new TossResult.Success(done(c)));
        approvalService.approve(c);
        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        return c;
    }

    private static TossPayment canceledBy(ApproveCommand c, String reason) {
        return new TossPayment(
                c.paymentKey(),
                c.pgOrderId(),
                "CANCELED",
                c.amount(),
                null,
                true,
                List.of(new TossPayment.Cancel("tx-" + c.orderRef(), reason, c.amount(), "DONE")));
    }

    private void assertRefunded(ApproveCommand c, Refund refund) {
        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat(row.get("refunded_amount")).isEqualTo(AMOUNT);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT pg_transaction_key FROM refund WHERE id = ?",
                                String.class,
                                refund.getId()))
                .isEqualTo("tx-" + c.orderRef());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refund_item WHERE refund_id = ?",
                                Integer.class,
                                refund.getId()))
                .isEqualTo(1);
        assertThat(refundJournals(refund)).isEqualTo(1);
    }

    private int refundJournals(Refund refund) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal WHERE ref_type = 'REFUND_SUCCEEDED' AND ref_id = ?",
                Integer.class,
                String.valueOf(refund.getId()));
    }

    private void assertFailed(ApproveCommand c, Refund refund, String failureCode)
            throws Exception {
        assertResolved(c, refund, "FAILED", 0);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT failure_code FROM refund WHERE id = ?",
                                String.class,
                                refund.getId()))
                .isEqualTo(failureCode);
    }

    private void awaitBlockedPaymentUpdate() {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            // 트랜잭션 안에서는 pg_stat_activity가 첫 조회 값으로 고정된다
            jdbc.queryForList("SELECT pg_stat_clear_snapshot()");
            Integer blocked =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                    + " AND query LIKE '%UPDATE payment%'",
                            Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("보상의 결제 UPDATE가 잠금을 기다리지 않았다");
    }

    private void tossQuery(ApproveCommand c, TossResult result) {
        when(toss.getPayment(c.paymentKey())).thenReturn(result);
    }

    private void tossCancel(ApproveCommand c, TossResult result) {
        when(toss.cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId())))
                .thenReturn(result);
    }

    /** Core가 보내는 모양의 전액 시스템 보상 요청을 접수한다. */
    private Refund receive(ApproveCommand c) throws Exception {
        return receive(c, AMOUNT);
    }

    private Refund receive(ApproveCommand c, long amount) throws Exception {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("refundRequestId", UUID.randomUUID().toString());
        payload.put("orderRef", c.orderRef());
        payload.put("paymentId", paymentId(c.orderRef()));
        payload.put("purpose", "SYSTEM_COMPENSATION");
        payload.put("reason", "APPROVAL_UNKNOWN");
        payload.put("currency", "KRW");
        payload.put("amount", amount);
        ObjectNode seller = payload.putArray("items").addObject();
        seller.put("sellerOrderNumber", c.orderRef() + "-S1");
        seller.put("amount", amount);
        ObjectNode item = seller.putArray("orderItems").addObject();
        item.put("orderItemId", 301);
        item.put("quantity", 1);
        item.put("amount", amount);
        payload.put("requestedAt", "2026-11-21T00:59:59.123456789Z");
        return intake.receive(JSON.readTree(JSON.writeValueAsString(payload))).orElseThrow();
    }

    private void assertResolved(ApproveCommand c, Refund refund, String status, long amount)
            throws Exception {
        Map<String, Object> row =
                jdbc.queryForMap("SELECT * FROM refund WHERE id = ?", refund.getId());
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("refunded_amount")).isEqualTo(amount);
        assertThat(row.get("next_attempt_at")).isNull();
        List<JsonNode> results = refundResults(c);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("payload").get("status").asText()).isEqualTo(status);
    }

    /** 결과를 보내지 않고 PROCESSING으로 남아 있다. */
    private void assertHeld(ApproveCommand c, Refund refund) throws Exception {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM refund WHERE id = ?",
                                String.class,
                                refund.getId()))
                .isEqualTo("PROCESSING");
        assertThat(refundResults(c)).isEmpty();
    }

    private List<JsonNode> refundResults(ApproveCommand c) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (String row :
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE topic = 'payment.refund.results.v1'"
                                + " AND message_key = ?",
                        String.class,
                        c.orderRef())) {
            events.add(JSON.readTree(row));
        }
        return events;
    }

    private void makeDue(Refund refund) {
        jdbc.update(
                "UPDATE refund SET next_attempt_at = now() - INTERVAL '1' SECOND WHERE id = ?",
                refund.getId());
    }

    private Double secondsToNextAttempt(Refund refund) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM next_attempt_at - now()) FROM refund WHERE id = ?",
                Double.class,
                refund.getId());
    }
}
