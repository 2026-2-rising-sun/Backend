package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentScenario;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.payment.repository.PaymentAttemptJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Core와 독립 Payment 서버의 HTTP 접수·결과 조회를 연결한다. 예약 콜백을 제어하고 복구를 명시 구동해 주문·재고 반영을 확인한다. */
@SpringBootTest
class PaymentFlowIntegrationTest
        extends com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport {
    @Autowired PaymentClientTestConfiguration.BoundaryPaymentServer paymentServer;
    @Autowired PaymentDeliveryStore deliveries;
    @Autowired PaymentBoundaryReconciler boundary;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired private PaymentService paymentService;

    @Autowired private DevPaymentScenarioRegistry devRegistry;

    @Autowired private OrderJpaRepository orderRepository;

    @Autowired private PaymentAttemptJpaRepository paymentAttemptRepository;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    private String orderNumber;
    private Long salesInfoId;

    @BeforeEach
    void setUp() {
        paymentServer.reset();
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        paymentAttemptRepository.deleteAll();
        jdbc.update("DELETE FROM payment_delivery_request");
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(500L, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        salesStockRepository.save(new SalesStock(salesInfoId, 4, 1));

        orderNumber = "OD-FLOW-1";
        Order order =
                new Order(
                        orderNumber,
                        salesInfoId,
                        1,
                        10_000L,
                        "홍길동",
                        "010-1234-5678",
                        "11111111-1111-4111-8111-111111111111",
                        "테스트",
                        null,
                        Instant.now().plusSeconds(900));
        orderRepository.save(order);
    }

    @AfterEach
    void tearDown() {
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        paymentAttemptRepository.deleteAll();
        jdbc.update("DELETE FROM payment_delivery_request");
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        devRegistry.clear(orderNumber);
    }

    @DisplayName("INSTANT_SUCCESS 결제를 시작하면 PAID로 확정하고 예약 재고를 소진한다")
    @Test
    void confirmsInstantSuccessAndConsumesReservedStock() {
        startPaymentWithScenario(
                orderNumber,
                "11111111-1111-4111-8111-111111111111",
                PaymentScenario.INSTANT_SUCCESS);

        await().atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofMillis(50))
                .untilAsserted(
                        () -> {
                            boundary.reconcileOverdue();
                            Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                            assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
                            SalesStock s = salesStockRepository.findById(salesInfoId).orElseThrow();
                            // consumeReserved: reserved 1 → 0, available 4 그대로 (소진)
                            assertThat(s.getReserved()).isZero();
                            assertThat(s.getAvailable()).isEqualTo(4);
                        });
    }

    @DisplayName("INSTANT_FAIL 결제는 FAILED로 확정하고 예약 재고를 복구한다")
    @Test
    void confirmsInstantFailureAndRestoresReservedStock() {
        startPaymentWithScenario(
                orderNumber, "11111111-1111-4111-8111-111111111111", PaymentScenario.INSTANT_FAIL);

        await().atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofMillis(50))
                .untilAsserted(
                        () -> {
                            boundary.reconcileOverdue();
                            Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                            assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
                            SalesStock s = salesStockRepository.findById(salesInfoId).orElseThrow();
                            // restoreReserved: 예약 재고 1 → 0, 가용 재고 4 → 5
                            assertThat(s.getReserved()).isZero();
                            assertThat(s.getAvailable()).isEqualTo(5);
                        });
    }

    @DisplayName("DELAYED_SUCCESS 결제는 지연 후 PAID로 확정한다")
    @Test
    void confirmsDelayedSuccessAfterDelay() {
        PaymentExecution attempt =
                startPaymentWithScenario(
                        orderNumber,
                        "11111111-1111-4111-8111-111111111111",
                        PaymentScenario.DELAYED_SUCCESS);

        // 시작 직후: 아직 PAYMENT_CONFIRMING, attempt.status = PROCESSING
        assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        await().atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofMillis(100))
                .untilAsserted(
                        () -> {
                            boundary.reconcileOverdue();
                            Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                            assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
                        });
    }

    @DisplayName("본문에 시나리오가 없어도 개발 레지스트리에 지정한 시나리오를 적용한다")
    @Test
    void usesPreconfiguredScenarioWithoutScenarioInBody() {
        devRegistry.set(orderNumber, PaymentScenario.INSTANT_FAIL);

        startPaymentWithScenario(orderNumber, "11111111-1111-4111-8111-111111111111", null);

        await().atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofMillis(50))
                .untilAsserted(
                        () -> {
                            boundary.reconcileOverdue();
                            Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                            assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
                        });
    }

    @DisplayName("테스트 레지스트리의 시나리오를 바꾸면 변경된 값을 사용한다")
    @Test
    void usesUpdatedTestRegistryScenario() {
        devRegistry.set(orderNumber, PaymentScenario.INSTANT_FAIL);

        startPaymentWithScenario(
                orderNumber,
                "11111111-1111-4111-8111-111111111111",
                PaymentScenario.INSTANT_SUCCESS);

        await().atMost(Duration.ofSeconds(10))
                .pollDelay(Duration.ofMillis(50))
                .untilAsserted(
                        () -> {
                            boundary.reconcileOverdue();
                            Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                            assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
                        });
    }

    private com.shoppinglive.commercecore.payment.dto.PaymentExecution startPaymentWithScenario(
            String number, String memberId, PaymentScenario scenario) {
        if (scenario != null) {
            devRegistry.set(number, scenario);
        }
        return paymentService.startPayment(number, memberId);
    }
}
