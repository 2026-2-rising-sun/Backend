package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 결과 불명 조회: 토스 조회 결과별 상태·pg_call·분개·outbox와 다음 조회 시각. */
@ExtendWith(OutputCaptureExtension.class)
class UnknownPaymentPollerPostgresTest extends RecoveryPostgresTestSupport {

    void tossAnswers(ApproveCommand c, TossResult result) {
        when(toss.getPayment(c.paymentKey())).thenReturn(result);
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-02][PAY-CONFIRM-12] 조회가 DONE이면 트랜잭션 밖에서 저장된 paymentKey로 1번 조회하고 APPROVED·분개 1건·PaymentApproved outbox 1행. 만료 시각이 지났어도(주문이 닫혔어도) 같다")
    void doneApproves() {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        jdbc.update(
                "UPDATE payment SET expires_at = now() - INTERVAL '1' MINUTE WHERE order_ref = ?",
                c.orderRef());
        when(toss.getPayment(c.paymentKey()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .as("토스 조회 중 트랜잭션")
                                    .isFalse();
                            assertThat(pgCalls(c.orderRef())).last().isEqualTo("QUERY:UNKNOWN");
                            return new TossResult.Success(done(c));
                        });

        poller.poll();

        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("pg_status")).isEqualTo("DONE");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN", "QUERY:SUCCESS");
        assertThat(approvalEntries(c.orderRef()))
                .containsExactly("PG_CUSTODY:30000:0", "BUYER_DEPOSIT:0:30000");
        assertThat(outboxRows(c.orderRef())).hasSize(1);
        verify(toss, times(1)).getPayment(c.paymentKey());

        poller.poll();
        verify(toss, times(1)).getPayment(c.paymentKey());
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-02][PAY-CONFIRM-07] 조회가 DONE인데 금액이 다르면 승인하지 않고 응답 paymentKey를 전액 취소해 CANCELED")
    void doneMismatchCompensates() {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        TossPayment wrong =
                new TossPayment(c.paymentKey(), c.pgOrderId(), "DONE", c.amount() + 1, null, true);
        tossAnswers(c, new TossResult.Success(wrong));
        when(toss.cancel(eq(c.paymentKey()), anyString(), eq("cancel-" + c.pgOrderId())))
                .thenReturn(new TossResult.Success(withStatus(c, "CANCELED")));

        poller.poll();

        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertThat(pgCalls(c.orderRef()))
                .containsExactly("CONFIRM:UNKNOWN", "QUERY:SUCCESS", "CANCEL:SUCCESS");
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "EXPIRED"})
    @DisplayName(
            "[PAY-RECOVER-03] 조회가 ABORTED·EXPIRED면 CREATED로 돌아가 결제창·승인 시작·조회 시각을 비운다. 새 결제창으로 다시 결제할 수 있다")
    void abortedOrExpiredRevertsToCreated(String pgStatus) {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        tossAnswers(c, new TossResult.Success(withStatus(c, pgStatus)));

        poller.poll();

        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("pg_order_id")).isNull();
        assertThat(row.get("approving_at")).isNull();
        assertThat(row.get("next_check_at")).isNull();
        assertThat(row.get("check_deadline_at")).isNull();
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN", "QUERY:SUCCESS");
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();
        assertThat(payments.openCheckout(c.orderRef()).getPgOrderId())
                .isEqualTo(c.orderRef() + "-2");
    }

    @Test
    @DisplayName("[PAY-RECOVER-03] 조회가 CANCELED(전액 취소)면 분개 없이 CANCELED로 닫고 WARN 로그를 남긴다")
    void canceledCloses(CapturedOutput output) {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        tossAnswers(c, new TossResult.Success(withStatus(c, "CANCELED")));

        poller.poll();

        long id = paymentId(c.orderRef());
        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(row.get("next_check_at")).isNull();
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();
        assertThat(output.getAll())
                .contains("전액 취소돼 CANCELED로 닫았다: paymentId=" + id + System.lineSeparator());
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-03] 조회가 PARTIAL_CANCELED면 UNKNOWN을 유지하고 사람 확인용 ERROR 로그를 남긴 뒤 다음 조회를 예약한다. 같은 결과가 다시 와도 로그는 1번")
    void partialCanceledNeedsHuman(CapturedOutput output) {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        tossAnswers(c, new TossResult.Success(withStatus(c, "PARTIAL_CANCELED")));

        poller.poll();

        long id = paymentId(c.orderRef());
        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");
        assertThat(payment(c.orderRef()).get("pg_status")).isEqualTo("PARTIAL_CANCELED");
        assertThat(secondsToNextCheck(c.orderRef())).isCloseTo(30.0, within(2.0));
        assertThat(journals(c.orderRef())).isZero();

        jdbc.update("UPDATE payment SET next_check_at = now() WHERE order_ref = ?", c.orderRef());
        poller.poll();
        verify(toss, times(2)).getPayment(c.paymentKey());
        String line = "부분 취소 상태다: paymentId=" + id + ",";
        String all = output.getAll();
        assertThat(all).contains(line);
        assertThat(all.indexOf(line)).isEqualTo(all.lastIndexOf(line));
    }

    /** 불명이 된 뒤 지난 시간 → 다음 조회까지 남은 초(10초·30초·1분·5분). */
    @ParameterizedTest
    @CsvSource({"15, 30", "39, 30", "41, 60", "99, 60", "101, 300", "600, 300"})
    @DisplayName(
            "[PAY-RECOVER-03] 조회가 IN_PROGRESS면 UNKNOWN 유지, 불명이 된 뒤 지난 시간으로 다음 조회 간격(30초·1분·5분)을 고른다")
    void inProgressSchedulesNextCheck(int elapsedSeconds, int expectedDelay) {
        ApproveCommand c = unknown(Duration.ofSeconds(elapsedSeconds));
        tossAnswers(c, new TossResult.Success(withStatus(c, "IN_PROGRESS")));

        poller.poll();

        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");
        assertThat(payment(c.orderRef()).get("pg_status")).isEqualTo("IN_PROGRESS");
        assertThat(secondsToNextCheck(c.orderRef())).isCloseTo(expectedDelay, within(2.0));
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN", "QUERY:SUCCESS");
    }

    @Test
    @DisplayName("[PAY-RECOVER-03] 다음 조회는 조회 마감을 넘기지 않는다(LEAST)")
    void nextCheckNeverPassesDeadline() {
        ApproveCommand c = unknown(Duration.ofMinutes(29));
        tossAnswers(c, new TossResult.Success(withStatus(c, "IN_PROGRESS")));

        poller.poll();

        assertThat(secondsToNextCheck(c.orderRef())).isCloseTo(60.0, within(2.0));
    }

    @Test
    @DisplayName("[PAY-RECOVER-03] 토스 조회 중 예외가 나도 다음 조회는 이미 예약돼 있어 같은 결제를 곧바로 다시 조회하지 않는다")
    void exceptionDoesNotRetryImmediately() {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        when(toss.getPayment(c.paymentKey())).thenThrow(new IllegalStateException("예상 못한 오류"));

        poller.poll();
        poller.poll();

        verify(toss, times(1)).getPayment(c.paymentKey());
        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");
        assertThat(secondsToNextCheck(c.orderRef())).isCloseTo(30.0, within(2.0));
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN", "QUERY:UNKNOWN");
    }

    @Test
    @DisplayName("[PAY-RECOVER-03] 조회가 거절·보내지 않음·불명이면 UNKNOWN 유지, 호출 기록에 결과를 남기고 다음 조회를 예약한다")
    void failedQueriesKeepUnknown() {
        ApproveCommand declined = unknown(Duration.ofSeconds(15));
        ApproveCommand notSent = unknown(Duration.ofSeconds(15));
        ApproveCommand unknownAgain = unknown(Duration.ofSeconds(15));
        tossAnswers(declined, new TossResult.Declined("NOT_FOUND_PAYMENT", "결제를 찾을 수 없음"));
        tossAnswers(notSent, new TossResult.NotSent("연결 거부"));
        tossAnswers(unknownAgain, new TossResult.Unknown("읽기 타임아웃"));

        poller.poll();

        for (ApproveCommand c : new ApproveCommand[] {declined, notSent, unknownAgain}) {
            assertThat(status(c.orderRef())).as(c.orderRef()).isEqualTo("UNKNOWN");
            assertThat(secondsToNextCheck(c.orderRef())).isCloseTo(30.0, within(2.0));
        }
        assertThat(pgCalls(declined.orderRef())).last().isEqualTo("QUERY:DECLINED");
        assertThat(pgCalls(notSent.orderRef())).last().isEqualTo("QUERY:NOT_SENT");
        assertThat(pgCalls(unknownAgain.orderRef())).last().isEqualTo("QUERY:UNKNOWN");
    }

    @Test
    @DisplayName("[PAY-RECOVER-03] 다음 조회 시각 전에는 조회하지 않는다")
    void notDueIsSkipped() {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        jdbc.update(
                "UPDATE payment SET next_check_at = now() + INTERVAL '10' SECOND WHERE order_ref = ?",
                c.orderRef());

        poller.poll();

        verify(toss, never()).getPayment(c.paymentKey());
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN");
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-04] 마감 시각에 예약된 조회는 마지막으로 1번 하고, 그 결과가 확정이 아니면 다음 차례에 멈추며 그 결과를 lastPgStatus로 남긴다")
    void lastCheckAtDeadline(CapturedOutput output) {
        ApproveCommand c = unknown(Duration.ofMinutes(30).plusSeconds(1));
        jdbc.update(
                "UPDATE payment SET next_check_at = check_deadline_at WHERE order_ref = ?",
                c.orderRef());
        tossAnswers(c, new TossResult.Success(withStatus(c, "WAITING_FOR_DEPOSIT")));

        poller.poll();
        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");
        assertThat(payment(c.orderRef()).get("next_check_at")).isNotNull();
        poller.poll();

        verify(toss, times(1)).getPayment(c.paymentKey());
        assertThat(payment(c.orderRef()).get("next_check_at")).isNull();
        assertThat(output.getAll())
                .contains(
                        "payment.recovery.deadline paymentId="
                                + paymentId(c.orderRef())
                                + " orderRef="
                                + c.orderRef()
                                + " pgOrderId="
                                + c.pgOrderId()
                                + " lastPgStatus=WAITING_FOR_DEPOSIT");
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-04] 조회 마감이 지나면 토스를 부르지 않고 조회 대상에서 빼며 ERROR 로그를 1번 남긴다(paymentKey는 남기지 않는다)")
    void deadlineStopsChecking(CapturedOutput output) {
        ApproveCommand c = unknown(Duration.ofMinutes(31));
        jdbc.update(
                "UPDATE payment SET pg_status = 'IN_PROGRESS' WHERE order_ref = ?", c.orderRef());

        poller.poll();
        poller.poll();

        long id = paymentId(c.orderRef());
        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");
        assertThat(payment(c.orderRef()).get("next_check_at")).isNull();
        verify(toss, never()).getPayment(anyString());
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN");
        String line =
                "payment.recovery.deadline paymentId="
                        + id
                        + " orderRef="
                        + c.orderRef()
                        + " pgOrderId="
                        + c.pgOrderId()
                        + " lastPgStatus=IN_PROGRESS";
        String all = output.getAll();
        assertThat(all).contains(line);
        assertThat(all.indexOf(line)).isEqualTo(all.lastIndexOf(line));
        assertThat(all).doesNotContain(c.paymentKey());
    }
}
