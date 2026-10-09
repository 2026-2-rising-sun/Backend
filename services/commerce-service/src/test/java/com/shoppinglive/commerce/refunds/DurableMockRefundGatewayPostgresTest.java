package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.refunds.application.DurableMockRefundGateway;
import com.shoppinglive.commerce.refunds.application.MockRefundResultUnknownException;
import com.shoppinglive.commerce.refunds.application.MockRefundScenario;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.commerce.refunds.domain.MockRefundOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class DurableMockRefundGatewayPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired DurableMockRefundGateway gateway;

    @Test
    void sameRequestReusesOneImmutableGatewayResult() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "gateway-replay", java.util.List.of(a.getId())).refund();

        var first = gateway.execute(request.id(), request.refundAmount());
        var replay = gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.FAILED);

        assertThat(first.outcome()).isEqualTo(MockRefundOutcome.SUCCESS);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);
    }

    @Test
    void persistedSuccessSurvivesLostResponseAndRetry() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "gateway-response-lost", java.util.List.of(a.getId())).refund();

        assertThatThrownBy(() -> gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.UNKNOWN_AFTER_RESULT))
            .isInstanceOf(MockRefundResultUnknownException.class);

        var replay = gateway.execute(request.id(), request.refundAmount());

        assertThat(replay.outcome()).isEqualTo(MockRefundOutcome.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?", Integer.class,
            request.id())).isEqualTo(1);
    }

    @Test
    void missingResultCanBeRetriedWithoutInventingAnOutcome() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "gateway-no-result", java.util.List.of(a.getId())).refund();

        assertThatThrownBy(() -> gateway.execute(request.id(), request.refundAmount(), MockRefundScenario.UNKNOWN_BEFORE_RESULT))
            .isInstanceOf(MockRefundResultUnknownException.class);
        assertThat(gateway.find(request.id())).isNull();

        assertThat(gateway.execute(request.id(), request.refundAmount()).outcome()).isEqualTo(MockRefundOutcome.SUCCESS);
    }

    @Test
    void replayRejectsAnAmountDifferentFromThePersistedRequest() {
        var group = createAndPay();
        var request = refunds.request(MEMBER_A, group.groupNumber(), "gateway-amount", java.util.List.of(a.getId())).refund();
        gateway.execute(request.id(), request.refundAmount());

        assertThatThrownBy(() -> gateway.execute(request.id(), request.refundAmount() + 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("amount differs");
    }
}
