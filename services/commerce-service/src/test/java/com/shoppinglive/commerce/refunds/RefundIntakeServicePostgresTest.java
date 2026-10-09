package com.shoppinglive.commerce.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;

import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService;
import com.shoppinglive.commerce.refunds.application.RefundIntakeService;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.common.core.BusinessException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundIntakeServicePostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @MockitoSpyBean InMemoryShoppingClientStub shoppingSpy;

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

    @Test
    void legacyProductWithoutSellerCanStillBeRefunded() {
        var group = createAndPay();
        shopping.register(new ProductSnapshot(81001L, "A", null, null));

        var result = refunds.request(MEMBER_A, group.groupNumber(), "refund-legacy-owner", List.of(a.getId())).refund();

        assertThat(result.refundAmount()).isEqualTo(20000L);
        assertThat(jdbc.queryForObject("SELECT seller_id_snapshot FROM refund_target_order WHERE refund_request_id=?",
            String.class, result.id())).isNull();
    }

    @Test
    void concurrentSameMemberKeyAcrossGroupsReturnsConflictInsteadOfUniqueViolation() throws Exception {
        var firstGroup = createAndPay();
        var anotherItem = cart.add(MEMBER_A, 81001L, 1);
        var secondGroup = paymentGroups.create(MEMBER_A,
            List.of(new PaymentGroupService.Selection(
                anotherItem.getId(), anotherItem.getVersion())), "구매자", "01012345678", 10000L,
            "refund-order-second").group();
        var secondAttempt = paymentGroups.start(MEMBER_A, secondGroup.groupNumber(), "refund-payment-second");
        if (secondAttempt.getStatus() != PaymentStatus.SUCCESS) {
            paymentGroups.resolve(secondAttempt.getId());
        }

        CountDownLatch bothAtProductLookup = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) {
                bothAtProductLookup.countDown();
                if (!bothAtProductLookup.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent refund requests did not reach product lookup");
                }
            }
            return invocation.callRealMethod();
        }).when(shoppingSpy).findProducts(anyCollection());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> requestOutcome(firstGroup.groupNumber(), a.getId()));
            var second = executor.submit(() -> requestOutcome(secondGroup.groupNumber(), anotherItem.getId()));
            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(RefundIntakeService.RequestResult.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(BusinessException.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).map(BusinessException::errorCode))
                .containsOnly(com.shoppinglive.common.core.ErrorCode.CONFLICT);
        }
    }

    private Object requestOutcome(String groupNumber, Long cartItemId) {
        try {
            return refunds.request(MEMBER_A, groupNumber, "refund-cross-group-key", List.of(cartItemId));
        } catch (BusinessException exception) {
            return exception;
        }
    }
}
