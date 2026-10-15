package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.payment.entity.MockRefundOutcome;
import com.shoppinglive.commerce.payment.exception.MockRefundResultUnknownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class DurableMockRefundGatewayPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired DurableMockRefundGateway gateway;

    @DisplayName("같은 요청은 변경되지 않는 게이트웨이 결과 하나를 재사용한다")
    @Test
    void sameRequestReusesOneImmutableGatewayResult() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "gateway-replay",
                                java.util.List.of(a.getId()))
                        .refund();

        var first = gateway.execute(request.id(), request.refundAmount());
        var replay =
                gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.FAILED);

        assertThat(first.outcome()).isEqualTo(MockRefundOutcome.SUCCESS);
        assertThat(replay).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
    }

    @DisplayName("성공 응답 유실 후 재시도해도 저장된 성공 결과를 유지한다")
    @Test
    void persistedSuccessSurvivesLostResponseAndRetry() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "gateway-response-lost",
                                java.util.List.of(a.getId()))
                        .refund();

        assertThatThrownBy(
                        () ->
                                gateway.execute(
                                        request.id(),
                                        request.refundAmount(),
                                        MockRefundScenario.UNKNOWN_AFTER_RESULT))
                .isInstanceOf(MockRefundResultUnknownException.class);

        var replay = gateway.execute(request.id(), request.refundAmount());

        assertThat(replay.outcome()).isEqualTo(MockRefundOutcome.SUCCESS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
    }

    @DisplayName("결과가 없으면 결과를 임의로 만들지 않고 재시도를 허용한다")
    @Test
    void missingResultCanBeRetriedWithoutInventingAnOutcome() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "gateway-no-result",
                                java.util.List.of(a.getId()))
                        .refund();

        assertThatThrownBy(
                        () ->
                                gateway.execute(
                                        request.id(),
                                        request.refundAmount(),
                                        MockRefundScenario.UNKNOWN_BEFORE_RESULT))
                .isInstanceOf(MockRefundResultUnknownException.class);
        assertThat(gateway.find(request.id())).isNull();

        assertThat(gateway.execute(request.id(), request.refundAmount()).outcome())
                .isEqualTo(MockRefundOutcome.SUCCESS);
    }

    @DisplayName("재요청 금액이 저장된 요청과 다르면 거절한다")
    @Test
    void replayRejectsAnAmountDifferentFromThePersistedRequest() {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "gateway-amount",
                                java.util.List.of(a.getId()))
                        .refund();
        gateway.execute(request.id(), request.refundAmount());

        assertThatThrownBy(() -> gateway.execute(request.id(), request.refundAmount() + 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("환불 금액이");
    }
}
