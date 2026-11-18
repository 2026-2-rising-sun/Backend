package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 락 없이 여러 인스턴스가 같은 결제를 처리해도 상태 변경·분개·이벤트는 1번이다. */
class RecoveryConcurrencyPostgresTest extends RecoveryPostgresTestSupport {

    @Test
    @DisplayName(
            "[PAY-RECOVER-05] 조회기 두 개가 같은 결제를 동시에 집으면 하나만 토스를 조회한다. 그 조회 중 다시 집혀 두 조회가 모두 DONE이어도 APPROVED·분개·outbox는 1번")
    void twoPollersApplyOnce() throws Exception {
        ApproveCommand c = unknown(Duration.ofSeconds(15));
        CountDownLatch firstQuerying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(toss.getPayment(c.paymentKey()))
                .thenAnswer(
                        inv -> {
                            firstQuerying.countDown();
                            release.await(20, TimeUnit.SECONDS);
                            return new TossResult.Success(done(c));
                        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> poller.poll());
            assertThat(firstQuerying.await(20, TimeUnit.SECONDS)).isTrue();

            // 첫 조회가 다음 조회 시각을 미뤄 두었으므로 두 번째 조회기는 토스를 부르지 않는다
            poller.poll();
            verify(toss, times(1)).getPayment(c.paymentKey());

            // 다른 인스턴스가 늦게 본 것처럼 다시 조회할 차례로 만든다 → 두 조회가 겹친다
            jdbc.update(
                    "UPDATE payment SET next_check_at = now() WHERE order_ref = ?", c.orderRef());
            Future<?> second = pool.submit(() -> poller.poll());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (pgCalls(c.orderRef()).stream().filter("QUERY:UNKNOWN"::equals).count() < 2
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            release.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(toss, times(2)).getPayment(c.paymentKey());
        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(outboxRows(c.orderRef())).hasSize(1);
        assertThat(pgCalls(c.orderRef()))
                .containsExactly("CONFIRM:UNKNOWN", "QUERY:SUCCESS", "QUERY:SUCCESS");
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-05][PAY-RECOVER-01][PAY-RECOVER-02] 승인 응답이 3분 넘게 늦어 스위퍼·조회기가 먼저 APPROVED로 반영한 뒤 승인 응답 DONE이 와도 분개·outbox는 1번이고 승인 결과는 승인됨")
    void lateApprovalAfterPollerApplies() throws Exception {
        ApproveCommand c = newPayment();
        CountDownLatch confirming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            confirming.countDown();
                            release.await(20, TimeUnit.SECONDS);
                            return new TossResult.Success(done(c));
                        });
        when(toss.getPayment(c.paymentKey())).thenReturn(new TossResult.Success(done(c)));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ApprovalResult result;
        try {
            Future<ApprovalResult> approval = pool.submit(() -> approvals.approve(c));
            assertThat(confirming.await(20, TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "UPDATE payment SET approving_at = now() - INTERVAL '4' MINUTE WHERE order_ref = ?",
                    c.orderRef());

            sweeper.sweep();
            poller.poll();
            assertThat(status(c.orderRef())).isEqualTo("APPROVED");

            release.countDown();
            result = approval.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(result).isEqualTo(new ApprovalResult.Approved(paymentId(c.orderRef())));
        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(outboxRows(c.orderRef())).hasSize(1);
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-05][PAY-RECOVER-02] 조회기가 토스 조회 중일 때 승인 응답이 먼저 APPROVED로 반영해도 조회 결과 DONE은 다시 반영하지 않는다")
    void pollerAfterLateApproval() throws Exception {
        ApproveCommand c = newPayment();
        CountDownLatch confirming = new CountDownLatch(1);
        CountDownLatch releaseConfirm = new CountDownLatch(1);
        CountDownLatch querying = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            confirming.countDown();
                            releaseConfirm.await(20, TimeUnit.SECONDS);
                            return new TossResult.Success(done(c));
                        });
        when(toss.getPayment(c.paymentKey()))
                .thenAnswer(
                        inv -> {
                            querying.countDown();
                            releaseQuery.await(20, TimeUnit.SECONDS);
                            return new TossResult.Success(done(c));
                        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ApprovalResult> approval = pool.submit(() -> approvals.approve(c));
            assertThat(confirming.await(20, TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "UPDATE payment SET approving_at = now() - INTERVAL '4' MINUTE WHERE order_ref = ?",
                    c.orderRef());
            sweeper.sweep();
            Future<?> poll = pool.submit(() -> poller.poll());
            assertThat(querying.await(20, TimeUnit.SECONDS)).isTrue();

            releaseConfirm.countDown();
            assertThat(approval.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ApprovalResult.Approved.class);
            releaseQuery.countDown();
            poll.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(outboxRows(c.orderRef())).hasSize(1);
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS", "QUERY:SUCCESS");
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }
}
