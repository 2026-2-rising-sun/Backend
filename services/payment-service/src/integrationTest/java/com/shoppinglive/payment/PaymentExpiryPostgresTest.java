package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.config.PaymentException;
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

class PaymentExpiryPostgresTest extends RecoveryPostgresTestSupport {

    void expiresIn(ApproveCommand c, long millis) {
        jdbc.update(
                "UPDATE payment SET expires_at = now() + ? * INTERVAL '1' MILLISECOND WHERE order_ref = ?",
                millis,
                c.orderRef());
    }

    @Test
    @DisplayName(
            "[PAY-EXPIRE-01] 만료 시각이 지난 CREATED는 CANCELED가 되고 분개·이벤트는 없다. 만료 전 CREATED와 APPROVING은 그대로. 만료 뒤 승인 요청은 토스를 부르지 않는다")
    void expiresCreatedPayments() {
        ApproveCommand expired = newPayment();
        ApproveCommand alive = newPayment();
        ApproveCommand approving = approving();
        expiresIn(expired, -1000);
        expiresIn(approving, -1000);

        assertThat(expiry.expire()).isEqualTo(1);
        assertThat(expiry.expire()).isZero();

        assertThat(status(expired.orderRef())).isEqualTo("CANCELED");
        assertThat(status(alive.orderRef())).isEqualTo("CREATED");
        assertThat(status(approving.orderRef())).isEqualTo("APPROVING");
        assertThat(journals(expired.orderRef())).isZero();
        assertThat(outboxRows(expired.orderRef())).isEmpty();
        assertThatThrownBy(() -> approvals.approve(expired)).isInstanceOf(PaymentException.class);
        verify(toss, never()).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName(
            "[PAY-EXPIRE-01] 만료 시각 즈음 만료 작업과 승인 요청이 동시에 와도 한쪽만 반영된다: 토스를 불렀으면 CANCELED가 아니고, CANCELED면 토스를 부르지 않았다")
    void expiryAndApprovalRace() throws Exception {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv -> {
                            String pgOrderId = inv.getArgument(1);
                            String key = inv.getArgument(0);
                            long amount = inv.getArgument(2);
                            return new TossResult.Success(
                                    new com.shoppinglive.payment.client.toss.TossPayment(
                                            key, pgOrderId, "DONE", amount, null, true));
                        });
        List<ApproveCommand> commands = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ApproveCommand c = newPayment();
            expiresIn(c, 300 + i * 5L);
            commands.add(c);
        }
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(commands.size() + 1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (ApproveCommand c : commands) {
                futures.add(
                        pool.submit(
                                () -> {
                                    go.await(10, TimeUnit.SECONDS);
                                    Thread.sleep(250 + (long) (Math.random() * 150));
                                    try {
                                        approvals.approve(c);
                                    } catch (PaymentException expiredOrClosed) {
                                        // 만료에 졌다
                                    }
                                    return null;
                                }));
            }
            futures.add(
                    pool.submit(
                            () -> {
                                go.await(10, TimeUnit.SECONDS);
                                long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
                                while (System.nanoTime() < end) {
                                    expiry.expire();
                                }
                                return null;
                            }));
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        for (ApproveCommand c : commands) {
            String status = status(c.orderRef());
            boolean confirmCalled = pgCalls(c.orderRef()).contains("CONFIRM:SUCCESS");
            assertThat(status).as(c.orderRef()).isIn("APPROVED", "CANCELED");
            assertThat(confirmCalled).as(c.orderRef()).isEqualTo(status.equals("APPROVED"));
            assertThat(journals(c.orderRef())).isEqualTo(confirmCalled ? 1 : 0);
        }
    }
}
