package com.shoppinglive.commerce.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 처리 예약이 <b>커밋 이후</b>에 일어나는지 검증한다.
 *
 * <p><b>막으려는 사고:</b> {@code startPayment} 는 {@code @Transactional} 이고, 예전에는 아직
 * 커밋되지 않은 상태에서 {@code MockPaymentEngine.schedule} 을 호출했다. {@code INSTANT_*}
 * 시나리오는 지연 없이 다른 스레드에서 바로 실행되므로, 그 스레드가 커밋보다 먼저 도착하면
 * {@code paymentAttemptRepository.findById} 가 빈 결과를 받는다. {@code resolvePayment} 는
 * 그때 조용히 {@code return} 하므로 로그조차 남지 않고, 결제는 영원히 {@code PROCESSING},
 * 주문은 영원히 {@code PAYMENT_CONFIRMING} 으로 멈춘다.
 *
 * <p>복구 경로도 없다. {@code PaymentDelayReconciler} 는 {@code scheduled_resolve_at} 이 지난
 * 건만 훑는데 {@code INSTANT_*} 는 그 값이 {@code null} 이고, {@code OrderExpirationScheduler}
 * 는 {@code PENDING_PAYMENT} 만 대상으로 하는데 이 주문은 이미 {@code PAYMENT_CONFIRMING} 이다.
 * 결과적으로 주문과 재고가 영구히 묶인다.
 *
 * <p><b>왜 바깥 트랜잭션을 만드나:</b> 실제 호출 경로에서도 저장과 커밋 사이에 같은 틈이
 * 있지만 폭이 마이크로초 단위라 빠른 장비에서는 거의 재현되지 않는다 (CI 의 2 코어 러너에서만
 * 간헐적으로 터졌다). 여기서는 바깥 트랜잭션으로 그 틈을 수백 밀리초로 넓혀 스케줄링 시점을
 * 결정적으로 검증한다. 수정 전 코드에서는 반드시 실패하고, 수정 후에는 반드시 통과한다.
 */
@SpringBootTest
class PaymentSchedulingAfterCommitTest {

    private static final long PRODUCT_ID = 510L;
    private static final String ORDER_NUMBER = "OD-COMMIT-1";

    /** 비동기 스레드가 커밋보다 먼저 도달할 시간을 충분히 준다. */
    private static final long WINDOW_MILLIS = 300L;

    @Autowired
    private PaymentService paymentService;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long salesInfoId;

    @BeforeEach
    void setUp() {
        clean();

        Sales sales = salesRepository.save(new Sales(PRODUCT_ID, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        salesStockRepository.save(new SalesStock(salesInfoId, 4, 1));

        orderRepository.save(new Order(
            ORDER_NUMBER, salesInfoId, 1, 10_000L, "홍길동", "010-1234-5678",
            passwordEncoder.encode("secret"), "테스트", null, Instant.now().plusSeconds(900)));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        paymentAttemptRepository.deleteAll();
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    /**
     * 커밋이 늦어져도 결제는 반드시 확정된다. 예약이 커밋 이후에 걸리면 비동기 스레드는 항상
     * 저장된 결제 건을 보게 된다.
     */
    @Test
    void 커밋이_늦어져도_INSTANT_SUCCESS_는_확정된다() {
        startPaymentInsideLongTransaction(PaymentScenario.INSTANT_SUCCESS);

        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ofMillis(50))
            .untilAsserted(() -> {
                Order order = orderRepository.findByOrderNumber(ORDER_NUMBER).orElseThrow();
                assertThat(order.getStatus())
                    .as("커밋 전에 예약이 걸리면 결제가 영원히 멈춘다")
                    .isEqualTo(OrderStatus.PAID);

                SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
                assertThat(stock.getReserved()).as("성공 시 배정분 소진").isZero();
                assertThat(stock.getAvailable()).isEqualTo(4);
            });
    }

    @Test
    void 커밋이_늦어져도_INSTANT_FAIL_은_확정되고_재고가_복구된다() {
        startPaymentInsideLongTransaction(PaymentScenario.INSTANT_FAIL);

        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ofMillis(50))
            .untilAsserted(() -> {
                Order order = orderRepository.findByOrderNumber(ORDER_NUMBER).orElseThrow();
                assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);

                SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
                assertThat(stock.getReserved()).isZero();
                assertThat(stock.getAvailable()).as("실패 시 배정분 복구").isEqualTo(5);
            });
    }

    /**
     * 트랜잭션이 롤백되면 결제 건 자체가 없으므로 예약도 걸리지 않아야 한다. 커밋 전에 예약을
     * 걸면 존재하지 않는 결제를 처리하려 드는 일이 생긴다.
     */
    @Test
    void 롤백되면_결제_처리가_예약되지_않는다() {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                paymentService.startPayment(
                    ORDER_NUMBER, "secret", PaymentScenario.INSTANT_SUCCESS);
                throw new IllegalStateException("의도적 롤백");
            });
        } catch (IllegalStateException expected) {
            // 롤백 유도용
        }

        sleep(500);

        Order order = orderRepository.findByOrderNumber(ORDER_NUMBER).orElseThrow();
        assertThat(order.getStatus())
            .as("롤백됐으니 결제 시작 전 상태 그대로")
            .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(paymentAttemptRepository.findAll()).isEmpty();
    }

    /**
     * 바깥 트랜잭션 안에서 결제를 시작하고, 커밋 전에 일부러 머무른다. 예약이 커밋 전에 걸리는
     * 구현이라면 이 시간 동안 비동기 스레드가 빈 DB 를 보고 조용히 포기한다.
     */
    private void startPaymentInsideLongTransaction(PaymentScenario scenario) {
        transactionTemplate.executeWithoutResult(status -> {
            paymentService.startPayment(ORDER_NUMBER, "secret", scenario);
            sleep(WINDOW_MILLIS);
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
