package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.payment.exception.MockRefundResultUnknownException;
import com.shoppinglive.payment.service.DurableMockRefundGateway;
import com.shoppinglive.payment.service.MockRefundScenario;
import com.shoppinglive.payment.service.RefundMockEngine;
import com.shoppinglive.payment.service.RefundRecoveryStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "commercecore.refunds.execution-enabled=true",
            "commercecore.refunds.recovery.initial-delay=PT24H"
        })
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class RefundReconcilerPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundQueryService queries;
    @Autowired RefundBoundaryReconciler reconciler;
    @Autowired com.shoppinglive.commercecore.payment.service.RefundBoundaryTestDriver executions;
    @Autowired RefundRecoveryStore recovery;
    @Autowired RefundMockEngine mockEngine;
    @Autowired DurableMockRefundGateway gateway;

    @DisplayName("메모리 콜백이 없어도 저장된 대기 환불을 복구한다")
    @Test
    void persistedPendingRequestIsRecoveredWhenItsInMemoryCallbackIsMissing() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "scan-no-callback",
                                List.of(a.getId()))
                        .refund();
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
        assertThat(reconciler.reconcileDue()).isZero();
    }

    @DisplayName("복구 작업자 두 명이 같은 환불을 처리해도 한 번만 반영한다")
    @Test
    void twoReconciliationWorkersApplyTheSameRequestOnlyOnce() throws Exception {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "scan-two-workers",
                                List.of(a.getId()))
                        .refund();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return reconciler.reconcileDue();
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return new RefundBoundaryReconciler(recovery, executions)
                                        .reconcileDue();
                            });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(
                        queries.get(MEMBER_A, group.groupNumber(), request.id())
                                .cumulativeRefundAmount())
                .isEqualTo(20000L);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
    }

    @DisplayName("7일이 지나도 만료된 임대와 저장된 게이트웨이 결과를 복구한다")
    @Test
    void expiredProcessingLeaseAndCommittedGatewayResultAreRecoveredAfterSevenDays() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "scan-interrupted",
                                List.of(a.getId()))
                        .refund();
        var abandoned = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        recovery.beginInvocation(abandoned);
        assertThatThrownBy(
                        () ->
                                gateway.execute(
                                        request.id(),
                                        request.refundAmount(),
                                        MockRefundScenario.UNKNOWN_AFTER_RESULT))
                .isInstanceOf(MockRefundResultUnknownException.class);
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET lease_until=clock_timestamp()-INTERVAL '1 second',"
                        + "requested_at=clock_timestamp()-INTERVAL '10 days' WHERE id=?",
                request.id());
        org.mockito.Mockito.clearInvocations(gateway);
        assertThat(new RefundBoundaryReconciler(recovery, executions).reconcileDue()).isEqualTo(1);
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
    }

    @DisplayName("한도가 소진된 미확정 환불은 1분마다 조회만 하며 자동 실패 처리하지 않는다")
    @Test
    void exhaustedUnknownOnlyQueriesEachPersistedMinuteAndNeverAutomaticallyFails() {
        var group = createAndPay();
        var request =
                refunds.request(MEMBER_A, group.groupNumber(), "scan-exhausted", List.of(a.getId()))
                        .refund();
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
                request.id());
        for (int minute = 0; minute < 3; minute++) {
            makeRefundDue(request.id());
            assertThat(reconciler.reconcileDue()).isZero();
            assertThat(
                            paymentServer.jdbc.queryForObject(
                                    "SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
                                            + "FROM refund_execution WHERE id=?",
                                    Boolean.class,
                                    request.id()))
                    .isTrue();
            verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
            org.mockito.Mockito.clearInvocations(gateway);
            assertThat(reconciler.reconcileDue()).isZero();
            verify(gateway, never()).find(anyLong());
        }
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resolved_at IS NULL FROM refund_request WHERE id=?",
                                Boolean.class,
                                request.id()))
                .isTrue();
    }

    @DisplayName("한도 소진 후 늦은 결과를 조회하면 추가 환불 없이 반영한다")
    @Test
    void lateResultAfterExhaustionIsAppliedByTheScannerWithoutSendingAnotherRefund() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "scan-late-result",
                                List.of(a.getId()))
                        .refund();
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
                request.id());
        reconciler.reconcileDue();
        gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.FAILED);
        org.mockito.Mockito.clearInvocations(gateway);
        makeRefundDue(request.id());
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.FAILED);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT retry_count FROM refund_execution WHERE id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(3);
    }

    @DisplayName("한 요청의 반영 실패가 다른 요청의 복구를 막지 않는다")
    @Test
    void oneApplicationFailureDoesNotPreventAnotherRequestFromRecovering() {
        var group = createAndPay();
        var first =
                refunds.request(MEMBER_A, group.groupNumber(), "scan-bad-a", List.of(a.getId()))
                        .refund();
        var second =
                refunds.request(MEMBER_A, group.groupNumber(), "scan-good-b", List.of(b.getId()))
                        .refund();
        long salesId =
                orders.findByPaymentGroupIdOrderByIdAsc(
                                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                        .orElseThrow()
                                        .getId())
                        .getFirst()
                        .getTradeSkuId();
        jdbc.update("UPDATE sku SET available=2147483647 WHERE id=?", salesId);
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        assertThat(queries.get(MEMBER_A, group.groupNumber(), first.id()).status())
                .isEqualTo(RefundStatus.PROCESSING);
        assertThat(queries.get(MEMBER_A, group.groupNumber(), second.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
        jdbc.update("UPDATE sku SET available=3 WHERE id=?", salesId);
        makeRefundDue(first.id());
        assertThat(reconciler.reconcileDue()).isEqualTo(1);
        assertThat(queries.get(MEMBER_A, group.groupNumber(), first.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
    }

    @DisplayName("한도 소진 후 조회 실패에도 1분 주기를 유지하고 즉시 반복하지 않는다")
    @Test
    void exhaustedLookupFailureKeepsTheOneMinuteScheduleAndDoesNotSpin() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "scan-query-failure",
                                List.of(a.getId()))
                        .refund();
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
                request.id());
        org.mockito.Mockito.doThrow(new IllegalStateException("일시적인 조회 실패"))
                .when(gateway)
                .find(request.id());
        assertThat(reconciler.reconcileDue()).isZero();
        verify(gateway, org.mockito.Mockito.times(1)).find(request.id());
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
                                        + "FROM refund_execution WHERE id=?",
                                Boolean.class,
                                request.id()))
                .isTrue();
        verify(gateway, never()).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.UNKNOWN);
    }

    @DisplayName("마지막 PG 재시도 후 Core 반영이 실패해도 Payment 성공과 결과 재적용 경로를 보존한다")
    @Test
    void applicationFailureAfterTheLastExecutionRetryAlsoSchedulesResultOnlyAfterOneMinute() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "scan-last-retry-apply-failure",
                                List.of(a.getId()))
                        .refund();
        for (int i = 0; i < 3; i++) {
            makeRefundDue(request.id());
            assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT))
                    .isFalse();
        }
        long salesId =
                orders.findByPaymentGroupIdOrderByIdAsc(
                                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                        .orElseThrow()
                                        .getId())
                        .getFirst()
                        .getTradeSkuId();
        jdbc.update("UPDATE sku SET available=2147483647 WHERE id=?", salesId);
        makeRefundDue(request.id());
        assertThatThrownBy(() -> executions.execute(request.id()))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT retry_count FROM refund_execution WHERE id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(3);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT status FROM refund_execution WHERE id=?",
                                String.class,
                                request.id()))
                .isEqualTo("SUCCESS");
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(gateway.find(request.id())).isNotNull();
        assertThat(reconciler.reconcileDue()).isZero();
    }
}
