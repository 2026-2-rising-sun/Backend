package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.refunds.application.DurableMockRefundGateway;
import com.shoppinglive.commerce.refunds.application.MockRefundResultUnknownException;
import com.shoppinglive.commerce.refunds.application.MockRefundScenario;
import com.shoppinglive.commerce.refunds.application.RefundExecutionService;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.commerce.refunds.application.RefundOutcomeService;
import com.shoppinglive.commerce.refunds.application.RefundRetryPolicy;
import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;
import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import com.shoppinglive.commerce.refunds.infrastructure.RefundRecoveryStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundRecoveryExecutionPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundExecutionService executions;
    @Autowired RefundRecoveryStore recovery;
    @Autowired RefundOutcomeService outcomes;
    @MockitoSpyBean DurableMockRefundGateway gateway;

    @Test
    void initialCallPlusThreeRetriesExhaustsWithoutNewMoneyFailureOrStockRestore() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-exhausted", List.of(a.getId())).refund();
        for (int call = 0; call < 4; call++) {
            makeRefundDue(request.id());
            assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT)).isFalse();
            assertThat(retryCount(request.id())).isEqualTo(call);
            assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.UNKNOWN);
        }
        makeRefundDue(request.id());
        var recreated = new RefundExecutionService(recovery, gateway, outcomes, new RefundRetryPolicy());
        assertThat(recreated.execute(request.id(), MockRefundScenario.SUCCESS)).isFalse();
        assertThat(retryCount(request.id())).isEqualTo(3);
        assertThat(gateway.find(request.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_action_at-clock_timestamp()) > 55 "
            + "FROM refund_request WHERE id=?", Boolean.class, request.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM refund_request WHERE id=?", String.class,
            request.id())).isEqualTo("recovery-exhausted");
        var groupId = groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId();
        assertThat(orders.findByPaymentGroupIdOrderByIdAsc(groupId)).extracting(order -> order.getStatus())
            .containsOnly(OrderStatus.PAID);
        assertThat(stocks.findById(orders.findByPaymentGroupIdOrderByIdAsc(groupId).getFirst().getSalesInfoId())
            .orElseThrow().getAvailable()).isEqualTo(3);
    }

    @Test
    void interruptedInitialProcessingConsumesOnlyTheNextRetryAndRecoversAfterSevenDays() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-interrupted", List.of(a.getId())).refund();
        var abandoned = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        assertThat(recovery.beginInvocation(abandoned)).contains(0);
        jdbc.update("UPDATE refund_request SET requested_at=clock_timestamp()-INTERVAL '10 days',"
            + "lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?", request.id());
        jdbc.update("UPDATE payment_attempt SET resolved_at=clock_timestamp()-INTERVAL '10 days' WHERE payment_group_id=?",
            abandoned.paymentGroupId());

        assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT)).isFalse();
        assertThat(retryCount(request.id())).isEqualTo(1);
        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(retryCount(request.id())).isEqualTo(2);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
    }

    @Test
    void knownResultAfterExhaustionIsAppliedWithoutConsumingOrSendingAnotherExecution() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-late-result", List.of(a.getId())).refund();
        assertThatThrownBy(() -> gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.UNKNOWN_AFTER_RESULT))
            .isInstanceOf(MockRefundResultUnknownException.class);
        jdbc.update("UPDATE refund_request SET status='UNKNOWN',retry_count=3,execution_started_at=requested_at WHERE id=?",
            request.id());
        var original = gateway.find(request.id());

        assertThat(executions.execute(request.id())).isTrue();
        assertThat(retryCount(request.id())).isEqualTo(3);
        assertThat(gateway.find(request.id())).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT lease_token IS NULL AND lease_until IS NULL FROM refund_request WHERE id=?",
            Boolean.class, request.id())).isTrue();
    }

    @Test
    void staleWorkerCannotApplyMoneyResultOrRestoreStockAfterAnotherWorkerClaims() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-stale-apply", List.of(a.getId())).refund();
        var old = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        gateway.execute(request.id(), request.refundAmount());
        jdbc.update("UPDATE refund_request SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?", request.id());
        var current = recovery.claim(request.id(), Duration.ofMinutes(1)).orElseThrow();
        assertThat(outcomes.apply(old, MockRefundOutcome.SUCCESS)).isFalse();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(executions.executeClaimed(current, MockRefundScenario.SUCCESS)).isTrue();
        assertThat(outcomes.apply(old, MockRefundOutcome.SUCCESS)).isFalse();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).cumulativeRefundAmount()).isEqualTo(20000L);
    }

    @Test
    void gatewayExecutionRunsOutsideTheBusinessTransactionAndGroupLock() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-lock-boundary", List.of(a.getId())).refund();
        Long groupId = groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT id FROM payment_group WHERE id=? FOR UPDATE NOWAIT", Long.class, groupId))
                .isEqualTo(groupId);
            return invocation.callRealMethod();
        }).when(gateway).execute(anyLong(), anyLong(), any(MockRefundScenario.class));
        assertThat(executions.execute(request.id())).isTrue();
    }

    @Test
    void confirmedFailureIsTerminalAndTheLeaseAndCounterAreNotReused() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-failure", List.of(a.getId())).refund();
        assertThat(executions.execute(request.id(), MockRefundScenario.FAILED)).isTrue();
        assertThat(executions.execute(request.id(), MockRefundScenario.SUCCESS)).isFalse();
        assertThat(retryCount(request.id())).isZero();
        assertThat(gateway.find(request.id()).outcome()).isEqualTo(MockRefundOutcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT lease_token IS NULL AND lease_until IS NULL FROM refund_request WHERE id=?",
            Boolean.class, request.id())).isTrue();
    }

    private int retryCount(long id) {
        return jdbc.queryForObject("SELECT retry_count FROM refund_request WHERE id=?", Integer.class, id);
    }

    @Test
    void unexpectedDeliveryFailureAlsoStaysUnknownAndRetainsTheSameRequestBudget() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "recovery-delivery-failure", List.of(a.getId())).refund();
        var firstCall = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCall.getAndSet(false)) throw new IllegalStateException("temporary delivery failure");
            return invocation.callRealMethod();
        }).when(gateway).execute(anyLong(), anyLong(), any(MockRefundScenario.class));

        assertThatThrownBy(() -> executions.execute(request.id())).isInstanceOf(IllegalStateException.class);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(retryCount(request.id())).isZero();
        assertThat(gateway.find(request.id())).isNull();
        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(retryCount(request.id())).isEqualTo(1);
    }
}
