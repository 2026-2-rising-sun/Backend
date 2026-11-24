package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.common.core.BusinessException;
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
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PaymentGroup group = mock(PaymentGroup.class);
    private final RefundQueryService service =
            new RefundQueryService(
                    groups,
                    orders,
                    new com.shoppinglive.commercecore.payment.repository.RefundQueryRepository(
                            jdbc));

    @BeforeEach
    void setUp() {
        when(groups.findByGroupNumberAndMemberId("PG", "buyer")).thenReturn(Optional.of(group));
        when(group.getId()).thenReturn(10L);
        when(group.getStatus()).thenReturn(OrderStatus.PAID);
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
        when(order.getUnitPrice()).thenReturn(500L);
        return order;
    }

    @Test
    @DisplayName("이전 정책 주문은 Payment를 호출하지 않고 환불 불가 사유를 반환한다")
    void legacyGroupsAreUnavailableWithoutPaymentCall() {
        doReturn(List.of(order(1, 101L), order(2, null)))
                .when(orders)
                .findByPaymentGroupIdOrderByIdAsc(10L);
        var targets = service.refundableTargets("buyer", "PG");
        assertThat(targets).hasSize(2);
        assertThat(targets)
                .allSatisfy(
                        target -> {
                            assertThat(target.refundable()).isFalse();
                            assertThat(target.unavailableReason())
                                    .isEqualTo("LEGACY_REFUND_UNAVAILABLE");
                        });
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
