package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commerce.client.shopping.ShoppingClient;
import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.entity.PaymentGroup;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.order.repository.PaymentGroupRepository;
import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import com.shoppinglive.commerce.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RefundableTargetsTest {
    private final PaymentGroupRepository groups = mock(PaymentGroupRepository.class);
    private final OrderJpaRepository orders = mock(OrderJpaRepository.class);
    private final PaymentAttemptJpaRepository attempts = mock(PaymentAttemptJpaRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PaymentGroup group = mock(PaymentGroup.class);
    private final PaymentAttempt payment = mock(PaymentAttempt.class);
    private final Instant now = Instant.parse("2026-10-17T00:00:00Z");
    private final RefundIntakeService service =
            new RefundIntakeService(
                    groups,
                    orders,
                    attempts,
                    mock(SalesJpaRepository.class),
                    mock(ShoppingClient.class),
                    jdbc,
                    Clock.fixed(now, ZoneOffset.UTC),
                    mock(RefundMockEngine.class),
                    false);

    @BeforeEach
    void setUp() {
        when(groups.findByGroupNumberAndMemberId("PG", "buyer")).thenReturn(Optional.of(group));
        when(group.getId()).thenReturn(10L);
        when(group.getPaymentId()).thenReturn(20L);
        when(group.getStatus()).thenReturn(OrderStatus.PAID);
        when(attempts.findById(20L)).thenReturn(Optional.of(payment));
        when(payment.getStatus()).thenReturn(PaymentStatus.SUCCESS);
        when(payment.getResolvedAt()).thenReturn(now.minusSeconds(60));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), eq(10L)))
                .thenReturn(List.of());
    }

    private Order order(long id, Long cartId) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        when(order.getSourceCartItemId()).thenReturn(cartId);
        when(order.getStatus()).thenReturn(OrderStatus.PAID);
        when(order.getProductNameSnapshot()).thenReturn("할인 상품");
        when(order.getQuantity()).thenReturn(2);
        when(order.getPayableAmount()).thenReturn(900L);
        return order;
    }

    @Test
    @DisplayName("본인 주문의 할인 반영 금액을 제공하고 직접 주문은 선택 환불에서 제외한다")
    void includesDiscountedAmountsAndExcludesDirectOrders() {
        doReturn(List.of(order(1, 101L), order(2, null)))
                .when(orders)
                .findByPaymentGroupIdOrderByIdAsc(10L);
        var targets = service.refundableTargets("buyer", "PG");
        assertThat(targets.getFirst().refundable()).isTrue();
        assertThat(targets.getFirst().refundAmount()).isEqualTo(900L);
        assertThat(targets.getFirst().quantity()).isEqualTo(2);
        assertThat(targets.get(1).unavailableReason()).isEqualTo("CART_ITEM_UNAVAILABLE");
    }

    @Test
    @DisplayName("실패하지 않은 기존 환불 대상은 제외하고 나머지 상품은 선택 가능하다")
    void excludesExistingRequestsAndAllowsRemainingItems() {
        doReturn(List.of(order(1, 101L), order(2, 102L)))
                .when(orders)
                .findByPaymentGroupIdOrderByIdAsc(10L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Long>>any(), eq(10L)))
                .thenReturn(List.of(1L));
        var targets = service.refundableTargets("buyer", "PG");
        assertThat(targets.getFirst().unavailableReason()).isEqualTo("ALREADY_REQUESTED");
        assertThat(targets.get(1).refundable()).isTrue();
    }

    @Test
    @DisplayName("장바구니 항목에 주문이 여러 개 연결되면 모호한 선택을 허용하지 않는다")
    void excludesAmbiguousCartLinks() {
        doReturn(List.of(order(1, 101L), order(2, 101L)))
                .when(orders)
                .findByPaymentGroupIdOrderByIdAsc(10L);
        assertThat(service.refundableTargets("buyer", "PG"))
                .allSatisfy(
                        target ->
                                assertThat(target.unavailableReason())
                                        .isEqualTo("CART_ITEM_AMBIGUOUS"));
    }

    @Test
    @DisplayName("168시간 경계와 미완료 결제는 선택 환불 불가 이유를 반환한다")
    void rejectsExpiredAndUnpaidGroups() {
        doReturn(List.of(order(1, 101L))).when(orders).findByPaymentGroupIdOrderByIdAsc(10L);
        when(payment.getResolvedAt()).thenReturn(now.minusSeconds(168 * 3600));
        assertThat(service.refundableTargets("buyer", "PG").getFirst().unavailableReason())
                .isEqualTo("REFUND_WINDOW_EXPIRED");
        when(group.getStatus()).thenReturn(OrderStatus.PENDING_PAYMENT);
        assertThat(service.refundableTargets("buyer", "PG").getFirst().unavailableReason())
                .isEqualTo("PAYMENT_NOT_COMPLETED");
    }

    @Test
    @DisplayName("다른 회원의 결제 묶음은 대상 정보를 노출하지 않는다")
    void hidesOtherMembersGroups() {
        when(groups.findByGroupNumberAndMemberId("PG", "other")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refundableTargets("other", "PG"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders, jdbc);
    }
}
