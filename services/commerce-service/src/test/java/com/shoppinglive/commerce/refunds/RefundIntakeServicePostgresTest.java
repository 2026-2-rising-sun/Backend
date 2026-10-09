package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.common.core.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundIntakeServicePostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;

    @Test
    void selectedCartItemRefundUsesItsFullOrderAmountAndIdempotentReplay() {
        var group = createAndPay();
        var result = refunds.request(MEMBER_A, group.groupNumber(), "refund-a", List.of(a.getId())).refund();
        assertThat(result.refundAmount()).isEqualTo(20000L);
        assertThat(result.targets()).hasSize(1);
        assertThat(result.targets().getFirst().cartItemId()).isEqualTo(a.getId());
        assertThat(result.targets().getFirst().quantity()).isEqualTo(2);
        assertThat(result.targets().getFirst().refundAmount()).isEqualTo(20000L);

        var replay = refunds.request(MEMBER_A, group.groupNumber(), "refund-a", List.of(a.getId()));
        assertThat(replay.created()).isFalse();
        assertThat(replay.refund().id()).isEqualTo(result.id());
        assertThatThrownBy(() -> refunds.request(MEMBER_A, group.groupNumber(), "refund-a", List.of(b.getId())))
            .isInstanceOf(BusinessException.class);
        assertThat(orders.findByPaymentGroupIdOrderByIdAsc(groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
            .orElseThrow().getId())).extracting(order -> order.getStatus()).containsOnly(OrderStatus.PAID);
    }

    @Test
    void emptyCartIdListMeansWholeGroupAndUnknownOrDuplicateIdsAreRejected() {
        var group = createAndPay();
        var result = refunds.request(MEMBER_A, group.groupNumber(), "refund-all", List.of()).refund();
        assertThat(result.refundAmount()).isEqualTo(25000L);
        assertThat(result.targets()).hasSize(2);

        assertThatThrownBy(() -> refunds.request(MEMBER_A, group.groupNumber(), "refund-missing", List.of(999999L)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> refunds.request(MEMBER_A, group.groupNumber(), "refund-duplicate", List.of(a.getId(), a.getId())))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void refundWindowIsStrictlyLessThan168Hours() {
        var group = createAndPay();
        Long groupId = groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId();
        Long paymentId = groups.findById(groupId).orElseThrow().getPaymentId();
        jdbc.update("UPDATE payment_attempt SET resolved_at = CURRENT_TIMESTAMP - INTERVAL '168 hours' WHERE id=?", paymentId);
        assertThatThrownBy(() -> refunds.request(MEMBER_A, group.groupNumber(), "refund-expired", List.of(a.getId())))
            .isInstanceOf(BusinessException.class);
    }
}
