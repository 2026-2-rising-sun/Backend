package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 같은 결제에 승인 요청이 동시에 와도 CREATED→APPROVING 조건부 UPDATE가 한 건만 통과한다. */
class ApprovalConcurrencyPostgresTest extends ApprovalPostgresTestSupport {

    @Test
    @DisplayName("[PAY-CONFIRM-05][PAY-CONFIRM-04] 동시 승인 8건이면 토스 호출은 1번, 나머지는 처리 중이고 승인 뒤 재요청은 승인됨")
    void concurrentApprovalsCallTossOnce() throws Exception {
        ApproveCommand c = newPayment();
        CountDownLatch losersDone = new CountDownLatch(1);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            // 이긴 요청은 진 요청들이 모두 답할 때까지 토스 응답을 기다린다
                            losersDone.await(20, TimeUnit.SECONDS);
                            return new TossResult.Success(done(c));
                        });

        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ApprovalResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    go.await(10, TimeUnit.SECONDS);
                                    return approvals.approve(c);
                                }));
            }
            go.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (futures.stream().filter(Future::isDone).count() < threads - 1
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            List<ApprovalResult> losers = new ArrayList<>();
            for (Future<ApprovalResult> f : futures) {
                if (f.isDone()) {
                    losers.add(f.get());
                }
            }
            assertThat(losers).hasSize(threads - 1).allMatch(ApprovalResult.Pending.class::isInstance);
            assertThat(status(c.orderRef())).isEqualTo("APPROVING");
            losersDone.countDown();

            List<ApprovalResult> all = new ArrayList<>();
            for (Future<ApprovalResult> f : futures) {
                all.add(f.get(20, TimeUnit.SECONDS));
            }
            assertThat(all).filteredOn(ApprovalResult.Approved.class::isInstance).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        assertThat(status(c.orderRef())).isEqualTo("APPROVED");
        assertThat(pgCalls(c.orderRef())).containsExactly("CONFIRM:SUCCESS");
        assertThat(journals(c.orderRef())).isEqualTo(1);
        assertThat(outboxRows(c.orderRef())).hasSize(1);

        assertThat(approvals.approve(c)).isInstanceOf(ApprovalResult.Approved.class);
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }
}
