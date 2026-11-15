package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 승인 ②③: 토스 결과별 상태·pg_call·분개·outbox. 토스 호출 중에는 트랜잭션이 없고 APPROVING이 이미 커밋돼 있어야 한다. */
@ExtendWith(OutputCaptureExtension.class)
class ApprovalOutcomePostgresTest extends ApprovalPostgresTestSupport {
    @Autowired TransactionTemplate tx;

    static TossResult unknown() {
        return new TossResult.Unknown("읽기 타임아웃");
    }

    /** 토스 대역 안에서 확인: 트랜잭션 밖이고, APPROVING·paymentKey·호출 기록이 다른 연결에서 보인다. */
    TossResult assertOutsideTransaction(ApproveCommand c, int expectedCalls, TossResult result) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("토스 호출 중 트랜잭션")
                .isFalse();
        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("APPROVING");
        assertThat(row.get("pg_payment_key")).isEqualTo(c.paymentKey());
        assertThat(row.get("approving_at")).isNotNull();
        assertThat(pgCalls(c.orderRef()))
                .hasSize(expectedCalls)
                .last()
                .isEqualTo("CONFIRM:UNKNOWN");
        return result;
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-04][PAY-CONFIRM-06][PAY-CONFIRM-12][LED-APPROVE-01][PAY-EVENT-01] 토스 성공이면 트랜잭션 밖에서 1번 부르고 APPROVED·승인 분개·PaymentApproved outbox를 함께 남긴다")
    void success() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> assertOutsideTransaction(c, 1, new TossResult.Success(done(c))));

        ApprovalResult result = approvals.approve(c);

        long id = paymentId(c.orderRef());
        assertThat(result).isEqualTo(new ApprovalResult.Approved(id));
        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(((Timestamp) row.get("approved_at")).toInstant())
                .isEqualTo(Instant.parse("2026-11-10T05:02:11Z"));
        assertThat(row.get("pg_status")).isEqualTo("DONE");
        assertThat(row.get("partial_cancelable")).isEqualTo(true);
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT idempotency_key || '/' || pg_order_id FROM pg_call WHERE payment_id = ?",
                                String.class,
                                id))
                .isEqualTo("confirm-" + c.pgOrderId() + "/" + c.pgOrderId());
        assertThat(approvalEntries(c.orderRef()))
                .containsExactly("PG_CUSTODY:30000:0", "BUYER_DEPOSIT:0:30000");
        List<Map<String, Object>> events = outboxRows(c.orderRef());
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .containsEntry("event_type", "PaymentApproved")
                .containsEntry("topic", "payment.execution-results.v1");
        assertThat((String) events.get(0).get("payload"))
                .contains("\"aggregateId\":\"" + c.orderRef() + "\"")
                .contains("\"paymentId\":" + id)
                .contains("\"orderRef\":\"" + c.orderRef() + "\"")
                .contains("\"amount\":30000")
                .contains("\"approvedAt\":\"2026-11-10T05:02:11Z\"");
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-07] 토스 성공 응답 값이 다르면 APPROVED로 바꾸지 않고 응답의 paymentKey를 cancel-{pg_order_id}로 전액 취소한 뒤 CANCELED")
    void mismatchCompensates() {
        ApproveCommand c = newPayment();
        TossPayment other =
                new TossPayment("tgen_other", c.pgOrderId(), "DONE", c.amount() + 1, null, true);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(other));
        when(toss.cancel(anyString(), anyString(), anyString()))
                .thenReturn(new TossResult.Success(other));

        ApprovalResult result = approvals.approve(c);

        assertThat(result).isEqualTo(new ApprovalResult.Closed(paymentId(c.orderRef())));
        verify(toss)
                .cancel(
                        org.mockito.ArgumentMatchers.eq("tgen_other"),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq("cancel-" + c.pgOrderId()));
        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS", "CANCEL:SUCCESS");
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();
    }

    @Test
    @DisplayName("[PAY-CONFIRM-07] 보상 취소 결과가 불명이어도 CANCELED로 두고 취소 기록은 UNKNOWN으로 남긴다")
    void mismatchWithUnknownCancelStillCanceled() {
        ApproveCommand c = newPayment();
        TossPayment wrongOrder =
                new TossPayment(c.paymentKey(), "OD-other-1", "DONE", c.amount(), null, true);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(wrongOrder));
        when(toss.cancel(anyString(), anyString(), anyString())).thenReturn(unknown());

        assertThat(approvals.approve(c)).isInstanceOf(ApprovalResult.Closed.class);

        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS", "CANCEL:UNKNOWN");
        assertThat(journals(c.orderRef())).isZero();
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-07] 보상 취소가 거절되면 CANCELED는 두고 취소 기록에 결과 코드를 남기며 사람 확인용 ERROR 로그를 남긴다(자동 재시도 없음)")
    void compensationFailureNeedsHuman(CapturedOutput output) {
        ApproveCommand c = newPayment();
        TossPayment wrongAmount =
                new TossPayment(c.paymentKey(), c.pgOrderId(), "DONE", c.amount() + 1, null, true);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(wrongAmount));
        when(toss.cancel(anyString(), anyString(), anyString()))
                .thenReturn(new TossResult.Declined("NOT_CANCELABLE_PAYMENT", "취소할 수 없는 결제"));

        assertThat(approvals.approve(c)).isInstanceOf(ApprovalResult.Closed.class);

        long id = paymentId(c.orderRef());
        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS", "CANCEL:DECLINED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_code FROM pg_call WHERE payment_id = ? AND kind = 'CANCEL'",
                                String.class,
                                id))
                .isEqualTo("NOT_CANCELABLE_PAYMENT");
        verify(toss, times(1)).cancel(anyString(), anyString(), anyString());
        assertThat(output.getAll())
                .contains("[사람 확인 필요] 보상 취소가 확정되지 않았다: paymentId=" + id)
                .contains("orderRef=" + c.orderRef())
                .contains("paymentKey=" + c.paymentKey())
                .contains("amount=30000");
    }

    @Test
    @DisplayName("[PAY-CONFIRM-07] 보상 취소 직전에 다른 반영이 먼저 승인했으면 CANCELED로 선점하지 못하고 토스 취소를 부르지 않는다")
    void compensationSkipsApprovedPayment() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(done(c)));
        approvals.approve(c);
        long id = paymentId(c.orderRef());

        assertThat(outcomes.compensate(id, c.pgOrderId(), c.paymentKey())).isFalse();

        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS");
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-08] 첫 호출이 거절이면 CREATED로 돌아가 pg_order_id를 비우고 거절 코드를 남긴다. 같은 결제창으로 다시 승인하면 STALE_CHECKOUT")
    void declined() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Declined("REJECT_CARD_PAYMENT", "한도초과"));

        ApprovalResult result = approvals.approve(c);

        assertThat(result)
                .isEqualTo(
                        new ApprovalResult.Declined(
                                paymentId(c.orderRef()), "REJECT_CARD_PAYMENT", "한도초과"));
        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("approving_at")).isNull();
        assertThat(row.get("pg_order_id")).isNull();
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:DECLINED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result_code FROM pg_call WHERE payment_id = ?",
                                String.class,
                                paymentId(c.orderRef())))
                .isEqualTo("REJECT_CARD_PAYMENT");
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> approvals.approve(c))
                .hasFieldOrPropertyWithValue(
                        "error", com.shoppinglive.payment.config.PaymentError.STALE_CHECKOUT);

        // 새 결제창(2회차)으로는 다시 승인할 수 있다
        payments.openCheckout(c.orderRef());
        ApproveCommand retry =
                new ApproveCommand(
                        c.orderRef(), c.buyerId(), c.paymentKey(), c.orderRef() + "-2", c.amount());
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(done(retry)));
        assertThat(approvals.approve(retry)).isInstanceOf(ApprovalResult.Approved.class);
        verify(toss)
                .confirm(
                        c.paymentKey(),
                        retry.pgOrderId(),
                        c.amount(),
                        "confirm-" + retry.pgOrderId());
        assertThat(journals(c.orderRef())).isEqualTo(1);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-09] 첫 호출이 보내지 않음이면 CREATED로 돌아가고 PG_UNAVAILABLE 결과")
    void notSent() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.NotSent("연결 거부"));

        assertThat(approvals.approve(c))
                .isEqualTo(new ApprovalResult.PgUnavailable(paymentId(c.orderRef())));

        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(row.get("pg_order_id")).isNull();
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:NOT_SENT");
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-10][PAY-CONFIRM-12] 불명이면 같은 키로 1번 더 부르고(호출 전 기록 1행 추가), 그래도 불명이면 UNKNOWN(10초 뒤 조회, 30분 마감)")
    void unknownTwiceBecomesUnknown() {
        ApproveCommand c = newPayment();
        AtomicInteger n = new AtomicInteger();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> assertOutsideTransaction(c, n.incrementAndGet(), unknown()));

        Instant before = Instant.now();
        assertThat(approvals.approve(c))
                .isEqualTo(new ApprovalResult.Pending(paymentId(c.orderRef())));

        verify(toss, times(2))
                .confirm(c.paymentKey(), c.pgOrderId(), c.amount(), "confirm-" + c.pgOrderId());
        Map<String, Object> row = payment(c.orderRef());
        assertThat(row.get("status")).isEqualTo("UNKNOWN");
        assertThat(row.get("pg_order_id")).isEqualTo(c.pgOrderId());
        Instant next = ((Timestamp) row.get("next_check_at")).toInstant();
        Instant deadline = ((Timestamp) row.get("check_deadline_at")).toInstant();
        assertThat(Duration.between(before, next).toSeconds()).isBetween(8L, 12L);
        assertThat(Duration.between(before, deadline).toMinutes()).isBetween(29L, 31L);
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN", "CONFIRM:UNKNOWN");
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();
    }

    @Test
    @DisplayName("[PAY-CONFIRM-10] 불명 뒤 재호출이 성공이면 그대로 승인 반영한다")
    void unknownThenSuccess() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(unknown(), new TossResult.Success(done(c)));

        assertThat(approvals.approve(c)).isInstanceOf(ApprovalResult.Approved.class);

        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN", "CONFIRM:SUCCESS");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(outboxRows(c.orderRef())).hasSize(1);
    }

    @Test
    @DisplayName("[PAY-CONFIRM-10] 한 번이라도 불명이면 재호출이 거절·보내지 않음이어도 CREATED로 되돌리지 않고 UNKNOWN")
    void unknownThenDeclinedOrNotSentStaysUnknown() {
        for (TossResult second :
                List.<TossResult>of(
                        new TossResult.Declined("REJECT_CARD_PAYMENT", "한도초과"),
                        new TossResult.NotSent("연결 거부"))) {
            ApproveCommand c = newPayment();
            when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                    .thenReturn(unknown(), second);

            assertThat(approvals.approve(c))
                    .as(second.toString())
                    .isInstanceOf(ApprovalResult.Pending.class);

            Map<String, Object> row = payment(c.orderRef());
            assertThat(row.get("status")).as(second.toString()).isEqualTo("UNKNOWN");
            assertThat(row.get("pg_order_id")).isEqualTo(c.pgOrderId());
            assertThat(pgCalls(c.orderRef())).hasSize(2).first().isEqualTo("CONFIRM:UNKNOWN");
        }
    }

    @Test
    @DisplayName(
            "[PAY-CONFIRM-06][LED-APPROVE-01][PAY-EVENT-01] 같은 결제에 승인 반영이 동시에 와도(조회기와 경합) APPROVED·분개·outbox는 1번이고 취소하지 않는다")
    void concurrentApplyDoneOnce() throws Exception {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(unknown());
        approvals.approve(c);
        long id = paymentId(c.orderRef());
        assertThat(status(c.orderRef())).isEqualTo("UNKNOWN");

        int threads = 8;
        List<Long> callIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            callIds.add(outcomes.insertCall(id, PgCallKind.QUERY, c.pgOrderId(), null));
        }
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Long callId : callIds) {
                results.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await(10, TimeUnit.SECONDS);
                                    return outcomes.applyDone(id, callId, done(c));
                                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Boolean> r : results) {
                assertThat(r.get(30, TimeUnit.SECONDS)).as("모두 승인으로 끝난다").isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(approvalEntries(c.orderRef())).hasSize(2);
        assertThat(outboxRows(c.orderRef())).hasSize(1);
        assertThat(pgCalls(c.orderRef()))
                .filteredOn(s -> s.startsWith("QUERY"))
                .allMatch("QUERY:SUCCESS"::equals);
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("[PAY-CONFIRM-07] 토스는 승인했는데 우리 결제가 다른 상태(승인 반영 0행)면 분개·outbox 없이 보상 취소 대상이 된다")
    void applyDoneOnOtherStatusNeedsCompensation() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(unknown());
        approvals.approve(c);
        long id = paymentId(c.orderRef());
        jdbc.update("UPDATE payment SET status = 'CANCELED' WHERE id = ?", id);
        long callId = outcomes.insertCall(id, PgCallKind.QUERY, c.pgOrderId(), null);

        assertThat(outcomes.applyDone(id, callId, done(c))).isFalse();

        assertThat(status(c.orderRef())).isEqualTo("CANCELED");
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();

        when(toss.cancel(anyString(), anyString(), anyString())).thenReturn(unknown());
        assertThat(outcomes.compensate(id, c.pgOrderId(), c.paymentKey())).isTrue();
        verify(toss)
                .cancel(
                        org.mockito.ArgumentMatchers.eq(c.paymentKey()),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq("cancel-" + c.pgOrderId()));
        assertThat(pgCalls(c.orderRef())).contains("CANCEL:UNKNOWN");
    }

    @Test
    @DisplayName("[PAY-EVENT-01][LED-APPROVE-01] outbox 기록이 실패하면 승인 반영 전체가 롤백돼 APPROVED·분개가 남지 않는다")
    void outboxFailureRollsBack() {
        ApproveCommand c = newPayment();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new TossResult.Success(done(c)));
        // EventOutbox.append는 MANDATORY라 스텁 호출도 트랜잭션 안에서 한다
        tx.executeWithoutResult(
                s ->
                        doThrow(new IllegalStateException("outbox 실패"))
                                .when(outbox)
                                .append(anyString(), any()));

        assertThatThrownBy(() -> approvals.approve(c)).hasMessageContaining("outbox 실패");

        assertThat(status(c.orderRef())).isEqualTo("APPROVING");
        assertThat(journals(c.orderRef())).isZero();
        assertThat(outboxRows(c.orderRef())).isEmpty();
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:UNKNOWN");
    }
}
