package com.shoppinglive.commerce.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 결제 시작 → Mock 엔진 → 결과 확정 → DB · 재고 반영까지 e2e.
 *
 * <p>Awaitility 로 async 완료를 폴링. INSTANT · DELAYED · dev 레지스트리 지정 케이스.
 */
@SpringBootTest
class PaymentFlowIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DevPaymentScenarioRegistry devRegistry;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptRepository;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String orderNumber;
    private Long salesInfoId;

    @BeforeEach
    void setUp() {
        paymentAttemptRepository.deleteAll();
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(500L, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        salesStockRepository.save(new SalesStock(salesInfoId, 4, 1));

        orderNumber = "OD-FLOW-1";
        Order order = new Order(
            orderNumber, salesInfoId, 1, 10_000L, "홍길동", "010-1234-5678",
            passwordEncoder.encode("secret"), "테스트", null, Instant.now().plusSeconds(900));
        orderRepository.save(order);
    }

    @AfterEach
    void tearDown() {
        paymentAttemptRepository.deleteAll();
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        devRegistry.clear(orderNumber);
    }

    @Test
    void INSTANT_SUCCESS_결제_시작_후_짧게_기다리면_PAID_로_확정_재고_소진() {
        paymentService.startPayment(orderNumber, "secret", PaymentScenario.INSTANT_SUCCESS);

        await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(50))
            .untilAsserted(() -> {
                Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
                SalesStock s = salesStockRepository.findById(salesInfoId).orElseThrow();
                // consumeReserved: reserved 1 → 0, available 4 그대로 (소진)
                assertThat(s.getReserved()).isZero();
                assertThat(s.getAvailable()).isEqualTo(4);
            });
    }

    @Test
    void INSTANT_FAIL_이면_FAILED_확정_재고_복구() {
        paymentService.startPayment(orderNumber, "secret", PaymentScenario.INSTANT_FAIL);

        await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(50))
            .untilAsserted(() -> {
                Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
                SalesStock s = salesStockRepository.findById(salesInfoId).orElseThrow();
                // restoreReserved: reserved 1 → 0, available 4 → 5
                assertThat(s.getReserved()).isZero();
                assertThat(s.getAvailable()).isEqualTo(5);
            });
    }

    @Test
    void DELAYED_SUCCESS_는_짧게_기다린_후에_PAID_로_확정() {
        PaymentAttempt attempt = paymentService.startPayment(
            orderNumber, "secret", PaymentScenario.DELAYED_SUCCESS);

        // 시작 직후: 아직 PAYMENT_CONFIRMING, attempt.status = PROCESSING
        assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.PROCESSING);

        await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(100))
            .untilAsserted(() -> {
                Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
            });
    }

    @Test
    void dev_registry_사전지정_시나리오_body_scenario_없어도_적용() {
        devRegistry.set(orderNumber, PaymentScenario.INSTANT_FAIL);

        paymentService.startPayment(orderNumber, "secret", null);

        await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(50))
            .untilAsserted(() -> {
                Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                assertThat(o.getStatus()).isEqualTo(OrderStatus.FAILED);
            });
    }

    @Test
    void body_scenario가_dev_registry보다_우선() {
        devRegistry.set(orderNumber, PaymentScenario.INSTANT_FAIL);

        paymentService.startPayment(orderNumber, "secret", PaymentScenario.INSTANT_SUCCESS);

        await().atMost(Duration.ofSeconds(2)).pollDelay(Duration.ofMillis(50))
            .untilAsserted(() -> {
                Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
            });
    }
}
