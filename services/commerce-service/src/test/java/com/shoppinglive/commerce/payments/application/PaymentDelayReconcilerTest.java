package com.shoppinglive.commerce.payments.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciler 통합 테스트.
 *
 * <p>Mock 엔진을 우회하고 직접 DB 에 PROCESSING 결제 시도를 넣은 뒤 Reconciler 를 호출해
 * 확정되는지 확인. 재기동으로 in-process 스케줄러 콜백이 유실된 상황을 재현.
 */
@SpringBootTest
class PaymentDelayReconcilerTest {

    @Autowired
    private PaymentDelayReconciler reconciler;

    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptRepository;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long salesInfoId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        paymentAttemptRepository.deleteAll();
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(400L, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        salesStockRepository.save(new SalesStock(salesInfoId, 4, 1));

        Order order = new Order(
            "OD-RECON-1", salesInfoId, 1, 10_000L, "홍길동", "010-1234-5678",
            passwordEncoder.encode("secret"), "테스트", null, Instant.now().plusSeconds(900));
        // Order 를 PAYMENT_CONFIRMING 상태로 (결제 시작 완료 후) 세팅
        Order saved = orderRepository.save(order);
        orderId = saved.getId();
        // @Modifying 쿼리는 트랜잭션 필요 — TransactionTemplate 로 감싸서 호출
        new TransactionTemplate(transactionManager).execute(status ->
            orderRepository.transitionStatus(orderId, "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
    }

    @AfterEach
    void tearDown() {
        paymentAttemptRepository.deleteAll();
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    private PaymentAttempt insertProcessingAttempt(PaymentScenario scenario, Instant scheduled) {
        PaymentAttempt attempt = new PaymentAttempt(orderId, scenario, Instant.now());
        // scheduled_resolve_at 을 과거로 설정해 Reconciler 대상이 되게 함
        ReflectionTestUtils.setField(attempt, "scheduledResolveAt", scheduled);
        return paymentAttemptRepository.save(attempt);
    }

    @Test
    void 지연_초과된_PROCESSING_INSTANT_SUCCESS_는_PAID_로_확정() {
        PaymentAttempt attempt = insertProcessingAttempt(
            PaymentScenario.INSTANT_SUCCESS, Instant.now().minusSeconds(10));

        int reconciled = reconciler.reconcileOverdue();

        assertThat(reconciled).isEqualTo(1);
        PaymentAttempt refreshed = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(refreshed.getResolvedAt()).isNotNull();

        Order refreshedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(refreshedOrder.getStatus()).isEqualTo(OrderStatus.PAID);

        SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
        // consumeReserved: reserved 1 → 0, available 4 그대로
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(4);
    }

    @Test
    void 지연_초과된_INSTANT_FAIL_는_FAILED_로_확정_재고_복구() {
        insertProcessingAttempt(PaymentScenario.INSTANT_FAIL, Instant.now().minusSeconds(10));

        reconciler.reconcileOverdue();

        Order refreshedOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(refreshedOrder.getStatus()).isEqualTo(OrderStatus.FAILED);

        SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
        // restoreReserved: reserved 1 → 0, available 4 → 5
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(5);
    }

    @Test
    void Reconciler_두_번_실행해도_이중_처리_없음() {
        insertProcessingAttempt(PaymentScenario.INSTANT_SUCCESS, Instant.now().minusSeconds(10));

        reconciler.reconcileOverdue();
        int secondRun = reconciler.reconcileOverdue();

        // 두 번째 실행에서도 attempt 는 스캔 대상 아님 (status = SUCCESS)
        assertThat(secondRun).isZero();

        SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
        // consumeReserved 한 번만 적용
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getAvailable()).isEqualTo(4);
    }
}
