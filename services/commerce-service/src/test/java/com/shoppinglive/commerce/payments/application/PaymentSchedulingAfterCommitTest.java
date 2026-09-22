package com.shoppinglive.commerce.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 커밋 전/롤백 시 예약하지 않고, 커밋 후에만 실제 비동기 결제를 시작하는지 검증한다. */
@SpringBootTest
class PaymentSchedulingAfterCommitTest {

    private static final long PRODUCT_ID = 510L;
    private static final String ORDER_NUMBER = "OD-COMMIT-1";

    @MockitoSpyBean
    private MockPaymentEngine mockPaymentEngine;

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
        clearInvocations(mockPaymentEngine);

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
                    .as("커밋 직후 결제 콜백으로 확정된다")
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

        verify(mockPaymentEngine, never()).schedule(anyLong(), any());

        Order order = orderRepository.findByOrderNumber(ORDER_NUMBER).orElseThrow();
        assertThat(order.getStatus())
            .as("롤백됐으니 결제 시작 전 상태 그대로")
            .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(paymentAttemptRepository.findAll()).isEmpty();
    }

    /**
     * 바깥 트랜잭션 안에서는 예약 호출이 없고, 반환 후 커밋된 시점에는 한 번 호출됐음을 확인한다.
     */
    private void startPaymentInsideLongTransaction(PaymentScenario scenario) {
        transactionTemplate.executeWithoutResult(status -> {
            paymentService.startPayment(ORDER_NUMBER, "secret", scenario);
            verify(mockPaymentEngine, never()).schedule(anyLong(), any());
        });
        verify(mockPaymentEngine).schedule(anyLong(), any());
    }
}
