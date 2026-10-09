package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.refunds.application.DurableMockRefundGateway;
import com.shoppinglive.commerce.refunds.application.MockRefundResultUnknownException;
import com.shoppinglive.commerce.refunds.application.MockRefundScenario;
import com.shoppinglive.commerce.refunds.application.RefundExecutionService;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.commerce.refunds.application.RefundMockEngine;
import com.shoppinglive.commerce.refunds.application.RefundReconciler;
import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = {"commerce.refunds.execution-enabled=true", "commerce.refunds.recovery.initial-delay=PT24H"})
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundReconcilerPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundReconciler reconciler;
    @Autowired RefundExecutionService executions;
    @Autowired RefundRecoveryStore recovery;
    @MockitoBean RefundMockEngine mockEngine;
    @MockitoSpyBean DurableMockRefundGateway gateway;

    @Test
    void persistedPendingRequestIsRecoveredWhenItsInMemoryCallbackIsMissing() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-no-callback", List.of(a.getId())).refund();
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(reconciler.reconcileDue()).isZero();
    }

    @Test
    void twoReconciliationWorkersApplyTheSameRequestOnlyOnce() throws Exception {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-two-workers", List.of(a.getId())).refund();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return reconciler.reconcileDue(); });
            var second = pool.submit(() -> { start.await(); return new RefundReconciler(recovery, executions).reconcileDue(); });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).cumulativeRefundAmount()).isEqualTo(20000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);
    }

    @Test
    void expiredProcessingLeaseAndCommittedGatewayResultAreRecoveredAfterSevenDays() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-interrupted", List.of(a.getId())).refund();
        var abandoned = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        recovery.beginInvocation(abandoned);
        assertThatThrownBy(() -> gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.UNKNOWN_AFTER_RESULT))
            .isInstanceOf(MockRefundResultUnknownException.class);
        jdbc.update("UPDATE refund_request SET lease_until=clock_timestamp()-INTERVAL '1 second',"
            + "requested_at=clock_timestamp()-INTERVAL '10 days' WHERE id=?", request.id());
        org.mockito.Mockito.clearInvocations(gateway);
        assertThat(new RefundReconciler(recovery, executions).reconcileDue()).isEqualTo(1);
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
    }

    @Test
    void exhaustedUnknownOnlyQueriesEachPersistedMinuteAndNeverAutomaticallyFails() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-exhausted", List.of(a.getId())).refund();
        jdbc.update("UPDATE refund_request SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
            request.id());
        for (int minute = 0; minute < 3; minute++) {
            makeRefundDue(request.id());
            assertThat(reconciler.reconcileDue()).isZero();
            assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
                + "FROM refund_request WHERE id=?", Boolean.class, request.id())).isTrue();
            verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
            org.mockito.Mockito.clearInvocations(gateway);
            assertThat(reconciler.reconcileDue()).isZero();
            verify(gateway, never()).find(anyLong());
        }
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT resolved_at IS NULL FROM refund_request WHERE id=?", Boolean.class,
            request.id())).isTrue();
    }

    @Test
    void lateResultAfterExhaustionIsAppliedByTheScannerWithoutSendingAnotherRefund() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-late-result", List.of(a.getId())).refund();
        jdbc.update("UPDATE refund_request SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
            request.id());
        reconciler.reconcileDue();
        gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.FAILED);
        org.mockito.Mockito.clearInvocations(gateway);
        makeRefundDue(request.id());
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM refund_request WHERE id=?", Integer.class, request.id()))
            .isEqualTo(3);
    }

    @Test
    void oneApplicationFailureDoesNotPreventAnotherRequestFromRecovering() {
        var group = createAndPay();
        var first = refunds.request(MEMBER_A, group.groupNumber(), "scan-bad-a", List.of(a.getId())).refund();
        var second = refunds.request(MEMBER_A, group.groupNumber(), "scan-good-b", List.of(b.getId())).refund();
        long salesId = orders.findByPaymentGroupIdOrderByIdAsc(
            groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId()).getFirst().getSalesInfoId();
        jdbc.update("UPDATE sales_stock SET available=2147483647 WHERE sales_info_id=?", salesId);
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), first.id()).status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), second.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        jdbc.update("UPDATE sales_stock SET available=3 WHERE sales_info_id=?", salesId);
        makeRefundDue(first.id());
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), first.id()).status()).isEqualTo(RefundStatus.SUCCESS);
    }

    @Test
    void exhaustedLookupFailureKeepsTheOneMinuteScheduleAndDoesNotSpin() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-query-failure", List.of(a.getId())).refund();
        jdbc.update("UPDATE refund_request SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
            request.id());
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary lookup failure")).when(gateway).find(request.id());
        assertThat(reconciler.reconcileDue()).isZero();
        verify(gateway, org.mockito.Mockito.times(1)).find(request.id());
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
            + "FROM refund_request WHERE id=?", Boolean.class, request.id())).isTrue();
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.UNKNOWN);
    }

    @Test
    void applicationFailureAfterTheLastExecutionRetryAlsoSchedulesResultOnlyAfterOneMinute() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "scan-last-retry-apply-failure", List.of(a.getId())).refund();
        for (int i = 0; i < 3; i++) {
            makeRefundDue(request.id());
            assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT)).isFalse();
        }
        long salesId = orders.findByPaymentGroupIdOrderByIdAsc(
            groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId()).getFirst().getSalesInfoId();
        jdbc.update("UPDATE sales_stock SET available=2147483647 WHERE sales_info_id=?", salesId);
        makeRefundDue(request.id());
        assertThatThrownBy(() -> executions.execute(request.id())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM refund_request WHERE id=?", Integer.class, request.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
            + "FROM refund_request WHERE id=?", Boolean.class, request.id())).isTrue();
        assertThat(gateway.find(request.id())).isNotNull();
        assertThat(reconciler.reconcileDue()).isZero();
    }
}
