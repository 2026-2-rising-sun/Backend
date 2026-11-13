package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.service.OrderPaymentOutcomeService;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.*;
import com.shoppinglive.commercecore.sales.service.SalesService;
import com.shoppinglive.common.core.*;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

@ExtendWith(MockitoExtension.class)
class PaymentResultHandlerTest {
    @Mock PaymentDeliveryStore store;
    @Mock RefundOutcomeService refunds;
    @Mock TransactionTemplate tx;
    @Mock OrderJpaRepository orders;
    @Mock com.shoppinglive.commercecore.sku.service.SkuService stock;
    @Mock SalesService sales;
    @Mock JdbcTemplate jdbc;
    @Mock com.shoppinglive.commercecore.order.repository.PaymentGroupRepository groups;
    @Mock com.shoppinglive.commercecore.cart.repository.CartItemRepository cart;
    @Mock com.shoppinglive.commercecore.sales.repository.SalesJpaRepository salesRepository;
    @Mock com.shoppinglive.commercecore.order.service.PurchaseGuard guard;
    @Mock com.shoppinglive.commercecore.coupon.service.CouponReservationService coupons;
    @Mock com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events;
    PaymentResultHandler handler;
    final String requestId = "11111111-1111-4111-8111-111111111111";
    final Instant at = Instant.parse("2026-11-09T00:00:00Z");
    final PaymentDeliveryStore.Delivery pending =
            new PaymentDeliveryStore.Delivery(
                    7, requestId, "PAYMENT", "command", null, "PROCESSING");

    @BeforeEach
    void setup() {
        var orderOutcomes =
                new OrderPaymentOutcomeService(
                        groups,
                        mock(
                                com.shoppinglive.commercecore.order.service
                                        .OrderSheetLifecycleService.class),
                        orders,
                        cart,
                        salesRepository,
                        stock,
                        sales,
                        store,
                        guard,
                        tx,
                        jdbc,
                        coupons,
                        events);
        handler = new PaymentResultHandler(store, orderOutcomes, refunds, tx, jdbc);
    }

    PaymentExecution result(PaymentStatus status) {
        return new PaymentExecution(
                requestId,
                7L,
                1L,
                null,
                "OD",
                1000,
                PaymentScenario.INSTANT_SUCCESS,
                status,
                at,
                status.isConfirmedOutcome() ? at.plusSeconds(1) : null,
                at);
    }

    void request() {
        when(store.get(requestId)).thenReturn(pending);
        when(store.read("command", PaymentCommand.class))
                .thenReturn(new PaymentCommand(requestId, 7L, 1L, null, "OD", 1000, null));
    }

    void transaction() {
        org.mockito.Mockito.lenient()
                .doAnswer(
                        i -> {
                            ((java.util.function.Consumer<
                                                    org.springframework.transaction
                                                            .TransactionStatus>)
                                            i.getArgument(0))
                                    .accept(new SimpleTransactionStatus());
                            return null;
                        })
                .when(tx)
                .executeWithoutResult(any());
        org.mockito.Mockito.lenient()
                .when(tx.execute(any()))
                .thenAnswer(
                        i ->
                                ((org.springframework.transaction.support.TransactionCallback<
                                                        Object>)
                                                i.getArgument(0))
                                        .doInTransaction(new SimpleTransactionStatus()));
    }

    Order order() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(order.getSalesInfoId()).thenReturn(10L);
        when(order.getQuantity()).thenReturn(1);
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("단건 성공 결과는 주문을 완료하고 예약 재고를 소진한다")
    void successfulResultConsumesReservedStock() {
        request();
        transaction();
        order();
        when(orders.transitionStatus(1L, "PAYMENT_CONFIRMING", "PAID")).thenReturn(1);
        handler.payment(result(PaymentStatus.SUCCESS));
        verify(stock).consume(10L, 1);
        verify(sales, never()).restoreReserved(any(), anyInt());
        verify(store).record(pending, result(PaymentStatus.SUCCESS), "SUCCESS");
    }

    @Test
    @DisplayName("단건 실패 결과는 주문을 실패 처리하고 예약 재고를 복구한다")
    void failedResultRestoresStock() {
        request();
        transaction();
        order();
        when(orders.transitionStatus(1L, "PAYMENT_CONFIRMING", "FAILED")).thenReturn(1);
        handler.payment(result(PaymentStatus.FAILED));
        verify(stock).release(10L, 1);
        verifyNoInteractions(sales);
    }

    @Test
    @DisplayName("이미 반영한 결과는 주문과 재고를 다시 변경하지 않는다")
    void ignoresDuplicateTerminalResult() {
        request();
        var done =
                new PaymentDeliveryStore.Delivery(
                        7, requestId, "PAYMENT", "command", "result", "SUCCESS");
        when(store.get(requestId)).thenReturn(done);
        handler.payment(result(PaymentStatus.SUCCESS));
        verify(store).requireSameResult(done, result(PaymentStatus.SUCCESS));
        verifyNoInteractions(orders, stock, sales);
    }

    @Test
    @DisplayName("조건부 주문 전이에 실패하면 결과 적용을 롤백하고 재고를 변경하지 않는다")
    void failsWhenOrderTransitionDoesNotMatch() {
        request();
        transaction();
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(orders.findById(1L)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> handler.payment(result(PaymentStatus.SUCCESS)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(stock, sales);
        verify(store, never()).record(any(), any(), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = PaymentStatus.class,
            names = {"PROCESSING", "UNKNOWN"})
    @DisplayName("미확정 결과는 주문 실패나 예약 재고 복구를 만들지 않는다")
    void unconfirmedResultRetainsBusinessReservations(PaymentStatus status) {
        request();
        transaction();
        handler.payment(result(status));
        verifyNoInteractions(orders, stock, sales);
        verify(store).record(pending, result(status), status.name());
    }

    @Test
    @DisplayName("호환성 PENDING 상태를 새 실행 결과로 적용하지 않는다")
    void rejectsCompatibilityPendingResult() {
        request();
        assertThatThrownBy(() -> handler.payment(result(PaymentStatus.PENDING)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders, stock, sales);
    }

    @Test
    @DisplayName("저장된 전달 요청이 없으면 결과를 적용하지 않는다")
    void missingRequestDoesNotAffectOrders() {
        when(store.get(requestId)).thenThrow(new BusinessException(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> handler.payment(result(PaymentStatus.SUCCESS)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders, stock, sales);
    }

    @Test
    @DisplayName("결과가 먼저 반영된 뒤 도착한 미확정 HTTP 응답은 무시한다")
    void ignoresLateProcessingAfterEarlyResult() {
        request();
        var done =
                new PaymentDeliveryStore.Delivery(
                        7, requestId, "PAYMENT", "command", "result", "SUCCESS");
        when(store.get(requestId)).thenReturn(done);
        handler.payment(result(PaymentStatus.PROCESSING));
        verify(store, never()).requireSameResult(any(), any());
        verifyNoInteractions(orders, stock, sales);
    }

    @Test
    @DisplayName("결제 결과 금액이 전달 원문과 다르면 적용하지 않는다")
    void mismatchingAmountIsRejected() {
        request();
        var p = result(PaymentStatus.SUCCESS);
        var wrong =
                new PaymentExecution(
                        p.requestId(),
                        p.paymentId(),
                        p.orderId(),
                        p.paymentGroupId(),
                        p.referenceNumber(),
                        999,
                        p.scenario(),
                        p.status(),
                        p.requestedAt(),
                        p.resolvedAt(),
                        p.scheduledResolveAt());
        assertThatThrownBy(() -> handler.payment(wrong)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(orders, stock, sales);
    }
}
