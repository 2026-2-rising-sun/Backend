package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import com.shoppinglive.commerce.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 결제 시작 → Mock 엔진 → 결과 확정 → DB · 재고 반영까지 e2e.
 *
 * <p>Awaitility 로 async 완료를 폴링. INSTANT · DELAYED · dev 레지스트리 지정 케이스.
 *
 * <p><b>대기 시간이 10 초인 이유:</b> 원래 2 초였는데 CI 에서 첫 비동기 결제가 2.2 초 걸려 빌드가 깨졌다. 스레드풀 생성·JIT 워밍업·첫 DB 쓰기가
 * 한꺼번에 몰리는 클래스의 첫 테스트라 느린 러너에서는 2 초를 넘길 수 있다. Awaitility 는 조건이 충족되면 즉시 빠져나오므로 상한을 늘려도 정상 경로는 그대로
 * 빠르다 — 느릴 때 더 기다려줄 뿐이다.
 */
@SpringBootTest
class PaymentFlowIntegrationTest
        extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {

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
        paymentAttemptRepository.deleteAll();
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
        paymentAttemptRepository.deleteAll();
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
        PaymentAttempt attempt =
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
                            Order o = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
                            assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
                        });
    }

    private com.shoppinglive.commerce.payment.entity.PaymentAttempt startPaymentWithScenario(
            String number, String memberId, PaymentScenario scenario) {
        if (scenario != null) {
            devRegistry.set(number, scenario);
        }
        return paymentService.startPayment(number, memberId);
    }
}
