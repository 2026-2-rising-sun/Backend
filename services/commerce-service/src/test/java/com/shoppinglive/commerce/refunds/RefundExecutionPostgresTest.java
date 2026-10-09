package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.refunds.application.MockRefundScenario;
import com.shoppinglive.commerce.refunds.application.RefundExecutionService;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundExecutionPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundExecutionService executions;

    @Test
    void successRefundsTargetOrderAndRestoresSoldStockOnce() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "execute-success", List.of(a.getId())).refund();

        assertThat(executions.execute(request.id())).isTrue();
        assertThat(executions.execute(request.id())).isFalse();

        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).cumulativeRefundAmount()).isEqualTo(20000L);
        assertThat(refunds.getSeller(SELLER, request.id()).cumulativeRefundAmount()).isEqualTo(20000L);
        assertThatThrownBy(() -> refunds.getSeller(SELLER_B, request.id()))
            .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);
        var groupId = groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId();
        var groupOrders = orders.findByPaymentGroupIdOrderByIdAsc(groupId);
        assertThat(groupOrders).extracting(order -> order.getStatus())
            .containsExactly(OrderStatus.REFUNDED, OrderStatus.PAID);
        assertThat(stocks.findById(groupOrders.getFirst().getSalesInfoId()).orElseThrow().getAvailable()).isEqualTo(5);
        assertThat(stocks.findById(groupOrders.getLast().getSalesInfoId()).orElseThrow().getAvailable()).isEqualTo(4);
        assertThat(groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PAID);
        assertThat(attempts.findById(groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
            .orElseThrow().getPaymentId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void paymentGroupIsRefundedOnlyAfterEveryOrderIsRefunded() {
        var group = createAndPay();
        var first = refunds.request(MEMBER_A, group.groupNumber(), "partial-a", List.of(a.getId())).refund();
        assertThat(executions.execute(first.id())).isTrue();
        assertThat(groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.PAID);

        var second = refunds.request(MEMBER_A, group.groupNumber(), "partial-b", List.of(b.getId())).refund();
        assertThat(executions.execute(second.id())).isTrue();
        assertThat(groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getStatus())
            .isEqualTo(OrderStatus.REFUNDED);
        assertThat(orders.findByPaymentGroupIdOrderByIdAsc(
            groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId()))
            .extracting(order -> order.getStatus()).containsOnly(OrderStatus.REFUNDED);
    }

    @Test
    void persistedMoneySuccessIsAppliedAfterDatabaseFailureWithoutExecutingAgain() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "apply-retry", List.of(a.getId())).refund();
        var target = orders.findByPaymentGroupIdOrderByIdAsc(
            groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId()).getFirst();
        jdbc.update("UPDATE sales_stock SET available=2147483647 WHERE sales_info_id=?", target.getSalesInfoId());

        assertThatThrownBy(() -> executions.execute(request.id()))
            .isInstanceOf(RuntimeException.class);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(orders.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);

        jdbc.update("UPDATE sales_stock SET available=3 WHERE sales_info_id=?", target.getSalesInfoId());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(orders.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(stocks.findById(target.getSalesInfoId()).orElseThrow().getAvailable()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);
    }

    @Test
    void successfulRefundDoesNotRestoreUsedCoupon() {
        var group = createAndPayWithCoupon();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "used-coupon-refund", List.of(a.getId())).refund();
        assertThat(jdbc.queryForObject("SELECT status FROM member_coupon WHERE coupon_id=?", String.class,
            REFUND_COUPON)).isEqualTo("USED");

        assertThat(executions.execute(request.id())).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM member_coupon WHERE coupon_id=?", String.class,
            REFUND_COUPON)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE coupon_id=?", Integer.class,
            REFUND_COUPON)).isEqualTo(1);
        assertThat(groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getCouponId())
            .isEqualTo(REFUND_COUPON);
    }

    @Test
    void confirmedFailureIsTerminalAndDoesNotExecuteAgain() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "execute-failed", List.of(a.getId())).refund();

        assertThat(executions.execute(request.id(), MockRefundScenario.FAILED)).isTrue();
        assertThat(executions.execute(request.id())).isFalse();

        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT outcome FROM mock_refund_result WHERE refund_request_id=?", String.class,
            request.id())).isEqualTo("FAILED");
    }

    @Test
    void unknownWithoutGatewayResultCanBeRetriedUsingTheSameRequest() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "execute-unknown-before", List.of(a.getId())).refund();

        assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT)).isFalse();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isZero();

        assertThat(executions.execute(request.id())).isTrue();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
    }

    @Test
    void unknownAfterPersistedResultReusesItOnRetry() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "execute-unknown-after", List.of(a.getId())).refund();

        assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_AFTER_RESULT)).isFalse();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);

        assertThat(executions.execute(request.id())).isTrue();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);
    }

    @Test
    void cumulativeRefundCannotExceedThePaidGroupAmount() {
        var group = createAndPay();
        var first = refunds.request(MEMBER_A, group.groupNumber(), "execute-cap-a", List.of(a.getId())).refund();
        var second = refunds.request(MEMBER_A, group.groupNumber(), "execute-cap-b", List.of(b.getId())).refund();
        jdbc.update("UPDATE refund_request SET refund_amount=24000 WHERE id=?", first.id());

        assertThatThrownBy(() -> executions.execute(second.id()))
            .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            second.id())).isZero();
    }

    @Test
    void zeroAmountRefundDoesNotCallTheMockGateway() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "execute-zero", List.of(a.getId())).refund();
        jdbc.update("UPDATE refund_target_order SET refund_amount=0 WHERE refund_request_id=?", request.id());
        jdbc.update("UPDATE refund_request SET refund_amount=0 WHERE id=?", request.id());

        assertThat(executions.execute(request.id())).isTrue();

        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isZero();
    }

}
