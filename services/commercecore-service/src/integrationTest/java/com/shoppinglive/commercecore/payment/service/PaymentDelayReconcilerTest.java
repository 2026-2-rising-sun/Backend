package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentScenario;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.payment.repository.PaymentAttemptJpaRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Core 전달과 Payment 원본을 생성한 뒤 처리 예정 시각을 당겨 콜백 유실·재시작 복구를 확인한다. */
@SpringBootTest
class PaymentDelayReconcilerTest
        extends com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport {

    @Autowired private PaymentBoundaryReconciler reconciler;

    @Autowired private PaymentService paymentService;

    @Autowired private PaymentAttemptJpaRepository paymentAttemptRepository;

    @Autowired private OrderJpaRepository orderRepository;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    @Autowired private PlatformTransactionManager transactionManager;

    @Autowired private com.shoppinglive.commercecore.order.service.PaymentGroupService groups;
    @Autowired private com.shoppinglive.payment.service.DurableMockGateway gateway;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired PaymentClientTestConfiguration.BoundaryPaymentServer paymentServer;
    @Autowired private com.shoppinglive.payment.service.PaymentRecoveryStore store;

    @Autowired PaymentDeliveryStore deliveries;
    @Autowired PaymentDispatcher dispatcher;
    @Autowired com.shoppinglive.commercecore.payment.dto.DevPaymentScenarioRegistry scenarios;
    private Long salesInfoId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        paymentServer.reset();
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        paymentAttemptRepository.deleteAll();
        jdbc.update("DELETE FROM payment_delivery_request");
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(400L, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        salesStockRepository.save(new SalesStock(salesInfoId, 4, 1));

        Order order =
                new Order(
                        "OD-RECON-1",
                        salesInfoId,
                        1,
                        10_000L,
                        "홍길동",
                        "010-1234-5678",
                        "11111111-1111-4111-8111-111111111111",
                        "테스트",
                        null,
                        Instant.now().plusSeconds(900));
        // Order 를 PAYMENT_CONFIRMING 상태로 (결제 시작 완료 후) 세팅
        Order saved = orderRepository.save(order);
        orderId = saved.getId();
        // @Modifying 쿼리는 트랜잭션 필요 — TransactionTemplate 로 감싸서 호출
        new TransactionTemplate(transactionManager)
                .execute(
                        status ->
                                orderRepository.transitionStatus(
                                        orderId, "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
        prepareLegacyTradeFixtures();
    }

    @AfterEach
    void tearDown() {
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        paymentAttemptRepository.deleteAll();
        jdbc.update("DELETE FROM payment_delivery_request");
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    private PaymentExecution insertProcessingAttempt(PaymentScenario scenario, Instant scheduled) {
        jdbc.update("UPDATE orders SET status='PENDING_PAYMENT' WHERE id=?", orderId);
        scenarios.set("OD-RECON-1", scenario);
        var attempt = paymentService.startPayment("OD-RECON-1", MEMBER_A);
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET requested_at=?,scheduled_resolve_at=? WHERE id=?",
                java.sql.Timestamp.from(scheduled.minusSeconds(1)),
                java.sql.Timestamp.from(scheduled),
                attempt.getId());
        return attempt;
    }

    @DisplayName("지연된 INSTANT_SUCCESS 결제는 PAID로 확정한다")
    @Test
    void confirmsOverdueSuccessfulPaymentAsPaid() {
        PaymentExecution attempt =
                insertProcessingAttempt(
                        PaymentScenario.INSTANT_SUCCESS, Instant.now().minusSeconds(10));

        int reconciled = reconciler.reconcileOverdue();

        assertThat(reconciled).isEqualTo(1);
        var refreshed = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(refreshed.getStatus())
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.SUCCESS);
        assertThat(refreshed.getResolvedAt()).isNotNull();

        Order refreshedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(refreshedOrder.getStatus()).isEqualTo(OrderStatus.PAID);

        SalesStock stock = legacyStock(salesInfoId);
        // consumeReserved: reserved 1 → 0, available 4 그대로
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(4);
    }

    @DisplayName("미확정 결제를 복구하고 늦게 도착한 미확정 결과가 확정 성공을 덮지 못한다")
    @Test
    void unknownAttemptIsRecoveredAndLateUnknownCannotOverwriteConfirmedSuccess() {
        PaymentExecution attempt =
                insertProcessingAttempt(
                        PaymentScenario.INSTANT_SUCCESS, Instant.now().minusSeconds(10));
        var tx =
                new TransactionTemplate(
                        paymentServer.context.getBean(PlatformTransactionManager.class));
        assertThat(
                        tx.<Integer>execute(
                                status ->
                                        paymentAttemptRepository.markUnknownIfProcessing(
                                                attempt.getId())))
                .isEqualTo(1);
        assertThat(paymentAttemptRepository.findById(attempt.getId()).orElseThrow().getResolvedAt())
                .isNull();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        assertThat(legacyStock(salesInfoId).getReserved()).isEqualTo(1);

        assertThat(
                        tx.<Integer>execute(
                                status ->
                                        paymentAttemptRepository.resolveIfUnconfirmed(
                                                attempt.getId(), "TIMEOUT")))
                .isZero();
        assertThat(reconciler.reconcileOverdue()).isEqualTo(1);
        assertThat(
                        tx.<Integer>execute(
                                status ->
                                        paymentAttemptRepository.markUnknownIfProcessing(
                                                attempt.getId())))
                .isZero();
        assertThat(reconciler.reconcileOverdue()).isZero();
        assertThat(paymentAttemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.SUCCESS);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(legacyStock(salesInfoId).getReserved()).isZero();
    }

    @DisplayName("지연된 INSTANT_FAIL 결제는 FAILED로 확정하고 재고를 복구한다")
    @Test
    void confirmsOverdueFailedPaymentAndRestoresStock() {
        insertProcessingAttempt(PaymentScenario.INSTANT_FAIL, Instant.now().minusSeconds(10));

        reconciler.reconcileOverdue();

        Order refreshedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(refreshedOrder.getStatus()).isEqualTo(OrderStatus.FAILED);

        SalesStock stock = legacyStock(salesInfoId);
        // restoreReserved: 예약 재고 1 → 0, 가용 재고 4 → 5
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    @DisplayName("결제 복구를 두 번 실행해도 중복 처리하지 않는다")
    @Test
    void doesNotProcessPaymentTwiceAcrossReconciliationRuns() {
        insertProcessingAttempt(PaymentScenario.INSTANT_SUCCESS, Instant.now().minusSeconds(10));

        reconciler.reconcileOverdue();
        int secondRun = reconciler.reconcileOverdue();

        // 두 번째 실행에서도 attempt 는 스캔 대상 아님 (status = SUCCESS)
        assertThat(secondRun).isZero();

        SalesStock stock = legacyStock(salesInfoId);
        // consumeReserved 한 번만 적용
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(4);
    }

    @DisplayName("스캔 후 다른 콜백이 확정한 결제는 처리 수에 포함하지 않는다")
    @Test
    void excludesPaymentConfirmedAfterScanFromProcessedCount() {
        PaymentExecution attempt =
                insertProcessingAttempt(
                        PaymentScenario.INSTANT_SUCCESS, Instant.now().minusSeconds(10));
        assertThat(dispatcher.dispatch(deliveries.byPayment(attempt.getId()))).isTrue();
        // 이미 완료된 결제는 뒤늦게 실행된 프로세스 내부 콜백이 다시 처리할 수 없다.
        assertThat(dispatcher.dispatch(deliveries.byPayment(attempt.getId()))).isFalse();
        assertThat(reconciler.reconcileOverdue()).isZero();
        assertThat(legacyStock(salesInfoId).getReserved()).isZero();
    }

    private PaymentExecution overdueGroupAttempt(Long childId, String key) {
        var accepted =
                new TransactionTemplate(transactionManager)
                        .execute(
                                status -> {
                                    Order child = orderRepository.findById(childId).orElseThrow();
                                    var group = groups.bindSingle(child, key, null);
                                    group.transition(OrderStatus.PAYMENT_CONFIRMING);
                                    var request =
                                            deliveries.payment(
                                                    childId,
                                                    group.getId(),
                                                    group.getGroupNumber(),
                                                    group.getPayableAmount(),
                                                    key,
                                                    null);
                                    group.setPaymentId(request.paymentId());
                                    return request;
                                });
        dispatcher.payment(accepted.paymentId());
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET scheduled_resolve_at=CURRENT_TIMESTAMP WHERE id=?",
                accepted.paymentId());
        return accepted;
    }

    @DisplayName("승인 반영에 실패한 묶음은 다음 결제를 막지 않고 반영 재시도를 허용한다")
    @Test
    void failedApprovedGroupDoesNotBlockNextPaymentAndRemainsRetryable() {
        PaymentExecution failed = overdueGroupAttempt(orderId, "broken-group");
        jdbc.update("UPDATE sku SET reserved=0 WHERE id=?", salesInfoId);
        Long healthySales =
                salesRepository.save(new Sales(401L, 10_000L, SalesStatus.ON_SALE)).getId();
        salesStockRepository.save(new SalesStock(healthySales, 4, 1));
        addMissingLegacyTradeFixtures();
        Order healthyOrder =
                orderRepository.saveAndFlush(
                        new Order(
                                "OD-RECON-HEALTHY",
                                healthySales,
                                1,
                                10_000L,
                                "회원B",
                                "01012345678",
                                MEMBER_B,
                                "상품",
                                null,
                                Instant.now().plusSeconds(900)));
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                orderRepository.transitionStatus(
                                        healthyOrder.getId(),
                                        "PENDING_PAYMENT",
                                        "PAYMENT_CONFIRMING"));
        PaymentExecution healthy = overdueGroupAttempt(healthyOrder.getId(), "healthy-group");
        // 실제 묶음·원장·주문·재고 트랜잭션을 유지하면서 조회 순서를 고정한다.
        PaymentBoundaryReconciler recovery = reconciler;

        assertThat(recovery.reconcileOverdue()).isEqualTo(1);
        assertThat(gateway.lookup(failed.getId()))
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.SUCCESS);
        assertThat(paymentAttemptRepository.findById(failed.getId()).orElseThrow().getStatus())
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.SUCCESS);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        assertThat(orderRepository.findById(healthyOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(paymentAttemptRepository.findById(healthy.getId()).orElseThrow().getStatus())
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.SUCCESS);
        assertThat(legacyStock(healthySales).getReserved()).isZero();

        jdbc.update("UPDATE sku SET reserved=1 WHERE id=?", salesInfoId);
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET scheduled_resolve_at=CURRENT_TIMESTAMP WHERE id=?",
                failed.getId());
        assertThat(recovery.reconcileOverdue()).isEqualTo(1);
        assertThat(recovery.reconcileOverdue()).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(legacyStock(salesInfoId).getReserved()).isZero();
        assertThat(legacyStock(healthySales).getReserved()).isZero();
    }
}
