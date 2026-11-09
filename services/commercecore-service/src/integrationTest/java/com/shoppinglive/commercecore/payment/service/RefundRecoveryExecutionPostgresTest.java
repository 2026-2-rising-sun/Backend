package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.payment.entity.MockRefundOutcome;
import com.shoppinglive.payment.exception.MockRefundResultUnknownException;
import com.shoppinglive.payment.service.DurableMockRefundGateway;
import com.shoppinglive.payment.service.MockRefundScenario;
import com.shoppinglive.payment.service.RefundRecoveryStore;
import com.shoppinglive.payment.service.RefundRetryPolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class RefundRecoveryExecutionPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundQueryService queries;
    @Autowired com.shoppinglive.commercecore.payment.service.RefundBoundaryTestDriver executions;
    @Autowired RefundRecoveryStore recovery;
    @Autowired com.shoppinglive.payment.service.RefundOutcomeService outcomes;
    @Autowired DurableMockRefundGateway gateway;

    @DisplayName("최초 실행과 세 번의 재시도로 한도를 소진해도 새 금전 실패나 재고 복구를 만들지 않는다")
    @Test
    void initialCallPlusThreeRetriesExhaustsWithoutNewMoneyFailureOrStockRestore() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-exhausted",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        for (int call = 0; call < 4; call++) {
            makeRefundDue(request.id());
            assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT))
                    .isFalse();
            assertThat(retryCount(request.id())).isEqualTo(call);
            assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                    .isEqualTo(RefundStatus.UNKNOWN);
        }
        makeRefundDue(request.id());
        var recreated =
                new com.shoppinglive.payment.service.RefundExecutionService(
                        recovery, gateway, outcomes, new RefundRetryPolicy());
        assertThat(recreated.execute(request.id(), MockRefundScenario.SUCCESS)).isFalse();
        assertThat(retryCount(request.id())).isEqualTo(3);
        assertThat(gateway.find(request.id())).isNull();
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
                                        + "FROM refund_execution WHERE id=?",
                                Boolean.class,
                                request.id()))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT idempotency_key FROM refund_request WHERE id=?",
                                String.class,
                                request.id()))
                .isEqualTo("recovery-exhausted");
        var groupId =
                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        assertThat(orders.findByPaymentGroupIdOrderByIdAsc(groupId))
                .extracting(order -> order.getStatus())
                .containsOnly(OrderStatus.PAID);
        assertThat(
                        stocks.findById(
                                        orders.findByPaymentGroupIdOrderByIdAsc(groupId)
                                                .getFirst()
                                                .getSalesInfoId())
                                .orElseThrow()
                                .getAvailable())
                .isEqualTo(3);
    }

    @DisplayName("최초 처리 중단 후에는 다음 재시도 한 번만 소비하며 7일 뒤에도 복구한다")
    @Test
    void interruptedInitialProcessingConsumesOnlyTheNextRetryAndRecoversAfterSevenDays() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-interrupted",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        var abandoned = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        assertThat(recovery.beginInvocation(abandoned)).contains(0);
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET requested_at=clock_timestamp()-INTERVAL '10 days',"
                        + "lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                request.id());
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET resolved_at=clock_timestamp()-INTERVAL '10 days' WHERE payment_group_id=?",
                abandoned.paymentGroupId());

        assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT))
                .isFalse();
        assertThat(retryCount(request.id())).isEqualTo(1);
        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(retryCount(request.id())).isEqualTo(2);
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
    }

    @DisplayName("한도 소진 후 확인된 결과는 새 실행 없이 반영한다")
    @Test
    void knownResultAfterExhaustionIsAppliedWithoutConsumingOrSendingAnotherExecution() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-late-result",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        assertThatThrownBy(
                        () ->
                                gateway.execute(
                                        request.id(),
                                        request.refundAmount(),
                                        MockRefundScenario.UNKNOWN_AFTER_RESULT))
                .isInstanceOf(MockRefundResultUnknownException.class);
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
                request.id());
        var original = gateway.find(request.id());

        assertThat(executions.execute(request.id())).isTrue();
        assertThat(retryCount(request.id())).isEqualTo(3);
        assertThat(gateway.find(request.id())).isEqualTo(original);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT lease_token IS NULL AND lease_until IS NULL FROM refund_execution WHERE id=?",
                                Boolean.class,
                                request.id()))
                .isTrue();
    }

    @DisplayName("다른 작업자가 선점하면 이전 작업자는 금전 결과·재고 복구를 반영할 수 없다")
    @Test
    void staleWorkerCannotApplyMoneyResultOrRestoreStockAfterAnotherWorkerClaims() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-stale-apply",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        var old = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        gateway.execute(request.id(), request.refundAmount());
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                request.id());
        var current = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        assertThat(outcomes.apply(old, MockRefundOutcome.SUCCESS)).isFalse();
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.PROCESSING);
        assertThat(executions.executeClaimed(current, MockRefundScenario.SUCCESS)).isTrue();
        assertThat(outcomes.apply(old, MockRefundOutcome.SUCCESS)).isFalse();
        assertThat(
                        queries.get(MEMBER_A, group.groupNumber(), request.id())
                                .cumulativeRefundAmount())
                .isEqualTo(20000L);
    }

    @DisplayName("게이트웨이 실행은 업무 트랜잭션과 결제 묶음 잠금 밖에서 수행한다")
    @Test
    void gatewayExecutionRunsOutsideTheBusinessTransactionAndGroupLock() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-lock-boundary",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        Long groupId =
                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT id FROM payment_group WHERE id=? FOR UPDATE NOWAIT",
                                                    Long.class,
                                                    groupId))
                                    .isEqualTo(groupId);
                            return invocation.callRealMethod();
                        })
                .when(gateway)
                .execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(executions.execute(request.id())).isTrue();
    }

    @DisplayName("확정 실패는 최종 상태이며 임대와 실행 횟수를 재사용하지 않는다")
    @Test
    void confirmedFailureIsTerminalAndTheLeaseAndCounterAreNotReused() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-failure",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        assertThat(executions.execute(request.id(), MockRefundScenario.FAILED)).isTrue();
        assertThat(executions.execute(request.id(), MockRefundScenario.SUCCESS)).isFalse();
        assertThat(retryCount(request.id())).isZero();
        assertThat(gateway.find(request.id()).outcome()).isEqualTo(MockRefundOutcome.FAILED);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT lease_token IS NULL AND lease_until IS NULL FROM refund_execution WHERE id=?",
                                Boolean.class,
                                request.id()))
                .isTrue();
    }

    private int retryCount(long id) {
        return paymentServer.jdbc.queryForObject(
                "SELECT retry_count FROM refund_execution WHERE id=?", Integer.class, id);
    }

    @DisplayName("예상하지 못한 전송 실패도 미확정 상태와 같은 요청의 실행 예산을 유지한다")
    @Test
    void unexpectedDeliveryFailureAlsoStaysUnknownAndRetainsTheSameRequestBudget() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "recovery-delivery-failure",
                                List.of(a.getId()))
                        .refund();
        submitRefund(request.id());
        var firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            if (firstCall.getAndSet(false)) {
                                throw new IllegalStateException("일시적인 결과 전달 실패");
                            }
                            return invocation.callRealMethod();
                        })
                .when(gateway)
                .execute(anyLong(), anyLong(), any(MockRefundScenario.class));

        assertThatThrownBy(() -> executions.execute(request.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queries.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(retryCount(request.id())).isZero();
        assertThat(gateway.find(request.id())).isNull();
        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(retryCount(request.id())).isEqualTo(1);
    }
}
