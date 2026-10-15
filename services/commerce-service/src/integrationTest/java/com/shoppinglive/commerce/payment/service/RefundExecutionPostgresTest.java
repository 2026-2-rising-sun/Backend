package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import com.shoppinglive.commerce.payment.entity.RefundStatus;
import com.shoppinglive.common.core.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundExecutionPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundExecutionService executions;

    @DisplayName("환불 성공은 대상 주문을 환불 처리하고 판매 재고를 한 번만 복구한다")
    @Test
    void successRefundsTargetOrderAndRestoresSoldStockOnce() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "execute-success",
                                List.of(a.getId()))
                        .refund();

        assertThat(executions.execute(request.id())).isTrue();
        assertThat(executions.execute(request.id())).isFalse();

        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
        assertThat(
                        refunds.get(MEMBER_A, group.groupNumber(), request.id())
                                .cumulativeRefundAmount())
                .isEqualTo(20000L);
        assertThat(refunds.getSeller(SELLER, request.id()).cumulativeRefundAmount())
                .isEqualTo(20000L);
        assertThatThrownBy(() -> refunds.getSeller(SELLER_B, request.id()))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
        var groupId =
                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        var groupOrders = orders.findByPaymentGroupIdOrderByIdAsc(groupId);
        assertThat(groupOrders)
                .extracting(order -> order.getStatus())
                .containsExactly(OrderStatus.REFUNDED, OrderStatus.PAID);
        assertThat(
                        stocks.findById(groupOrders.getFirst().getSalesInfoId())
                                .orElseThrow()
                                .getAvailable())
                .isEqualTo(5);
        assertThat(
                        stocks.findById(groupOrders.getLast().getSalesInfoId())
                                .orElseThrow()
                                .getAvailable())
                .isEqualTo(4);
        assertThat(
                        groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(
                        attempts.findById(
                                        groups.findByGroupNumberAndMemberId(
                                                        group.groupNumber(), MEMBER_A)
                                                .orElseThrow()
                                                .getPaymentId())
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @DisplayName("결제 묶음은 모든 주문이 환불된 뒤에만 환불 완료로 변경한다")
    @Test
    void paymentGroupIsRefundedOnlyAfterEveryOrderIsRefunded() {
        var group = createAndPay();
        var first =
                refunds.request(MEMBER_A, group.groupNumber(), "partial-a", List.of(a.getId()))
                        .refund();
        assertThat(executions.execute(first.id())).isTrue();
        assertThat(
                        groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(OrderStatus.PAID);

        var second =
                refunds.request(MEMBER_A, group.groupNumber(), "partial-b", List.of(b.getId()))
                        .refund();
        assertThat(executions.execute(second.id())).isTrue();
        assertThat(
                        groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(
                        orders.findByPaymentGroupIdOrderByIdAsc(
                                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                        .orElseThrow()
                                        .getId()))
                .extracting(order -> order.getStatus())
                .containsOnly(OrderStatus.REFUNDED);
    }

    @DisplayName("금전 처리 성공 후 DB 반영에 실패하면 재실행 없이 저장된 결과를 반영한다")
    @Test
    void persistedMoneySuccessIsAppliedAfterDatabaseFailureWithoutExecutingAgain() {
        var group = createAndPay();
        var request =
                refunds.request(MEMBER_A, group.groupNumber(), "apply-retry", List.of(a.getId()))
                        .refund();
        var target =
                orders.findByPaymentGroupIdOrderByIdAsc(
                                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                        .orElseThrow()
                                        .getId())
                        .getFirst();
        jdbc.update(
                "UPDATE sales_stock SET available=2147483647 WHERE sales_info_id=?",
                target.getSalesInfoId());

        assertThatThrownBy(() -> executions.execute(request.id()))
                .isInstanceOf(RuntimeException.class);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.PROCESSING);
        assertThat(orders.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);

        jdbc.update(
                "UPDATE sales_stock SET available=3 WHERE sales_info_id=?",
                target.getSalesInfoId());
        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
        assertThat(orders.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.REFUNDED);
        assertThat(stocks.findById(target.getSalesInfoId()).orElseThrow().getAvailable())
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
    }

    @DisplayName("환불이 성공해도 이미 사용한 쿠폰은 복원하지 않는다")
    @Test
    void successfulRefundDoesNotRestoreUsedCoupon() {
        var group = createAndPayWithCoupon();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "used-coupon-refund",
                                List.of(a.getId()))
                        .refund();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE coupon_id=?",
                                String.class,
                                REFUND_COUPON))
                .isEqualTo("USED");

        assertThat(executions.execute(request.id())).isTrue();

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE coupon_id=?",
                                String.class,
                                REFUND_COUPON))
                .isEqualTo("USED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM member_coupon WHERE coupon_id=?",
                                Integer.class,
                                REFUND_COUPON))
                .isEqualTo(1);
        assertThat(
                        groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                                .orElseThrow()
                                .getCouponId())
                .isEqualTo(REFUND_COUPON);
    }

    @DisplayName("확정 환불 실패는 최종 상태이며 다시 실행하지 않는다")
    @Test
    void confirmedFailureIsTerminalAndDoesNotExecuteAgain() {
        var group = createAndPay();
        var request =
                refunds.request(MEMBER_A, group.groupNumber(), "execute-failed", List.of(a.getId()))
                        .refund();

        assertThat(executions.execute(request.id(), MockRefundScenario.FAILED)).isTrue();
        assertThat(executions.execute(request.id())).isFalse();

        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.FAILED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT outcome FROM mock_refund_result WHERE refund_request_id=?",
                                String.class,
                                request.id()))
                .isEqualTo("FAILED");
    }

    @DisplayName("게이트웨이 결과가 없는 미확정 환불은 같은 요청으로 재시도할 수 있다")
    @Test
    void unknownWithoutGatewayResultCanBeRetriedUsingTheSameRequest() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "execute-unknown-before",
                                List.of(a.getId()))
                        .refund();

        assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_BEFORE_RESULT))
                .isFalse();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isZero();

        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
    }

    @DisplayName("결과가 저장된 미확정 환불은 재시도 시 기존 결과를 재사용한다")
    @Test
    void unknownAfterPersistedResultReusesItOnRetry() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "execute-unknown-after",
                                List.of(a.getId()))
                        .refund();

        assertThat(executions.execute(request.id(), MockRefundScenario.UNKNOWN_AFTER_RESULT))
                .isFalse();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);

        makeRefundDue(request.id());
        assertThat(executions.execute(request.id())).isTrue();
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
    }

    @DisplayName("누적 환불액은 결제 묶음의 실제 결제액을 넘을 수 없다")
    @Test
    void cumulativeRefundCannotExceedThePaidGroupAmount() {
        var group = createAndPay();
        var first =
                refunds.request(MEMBER_A, group.groupNumber(), "execute-cap-a", List.of(a.getId()))
                        .refund();
        var second =
                refunds.request(MEMBER_A, group.groupNumber(), "execute-cap-b", List.of(b.getId()))
                        .refund();
        jdbc.update("UPDATE refund_request SET refund_amount=24000 WHERE id=?", first.id());

        assertThatThrownBy(() -> executions.execute(second.id()))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                second.id()))
                .isZero();
    }

    @DisplayName("0원 환불은 Mock 게이트웨이를 호출하지 않는다")
    @Test
    void zeroAmountRefundDoesNotCallTheMockGateway() {
        var group = createAndPay();
        var request =
                refunds.request(MEMBER_A, group.groupNumber(), "execute-zero", List.of(a.getId()))
                        .refund();
        jdbc.update(
                "UPDATE refund_target_order SET refund_amount=0 WHERE refund_request_id=?",
                request.id());
        jdbc.update("UPDATE refund_request SET refund_amount=0 WHERE id=?", request.id());

        assertThat(executions.execute(request.id())).isTrue();

        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isZero();
    }
}
