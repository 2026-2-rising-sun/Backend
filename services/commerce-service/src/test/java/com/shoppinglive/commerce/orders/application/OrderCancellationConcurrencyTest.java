package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 전 주문 취소의 동시성 안전을 검증한다.
 *
 * <p>시나리오 A: 두 스레드가 동시에 같은 주문 취소 요청 → 한 명 성공, 한 명 409. 재고는 한
 * 번만 복구.
 *
 * <p>시나리오 B: 취소 요청과 결제 시작 시뮬레이션(transitionStatus) 동시 → 정확히 한쪽만 성공.
 */
@SpringBootTest
class OrderCancellationConcurrencyTest {

    @Autowired
    private OrderService orderService;

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

    private String orderNumber;
    private String rawPassword;
    private Long salesInfoId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(200L, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        // 재고 4 남고 1 은 아래 주문에 배정됨 (available=4, reserved=1)
        salesStockRepository.save(new SalesStock(salesInfoId, 4, 1));

        orderNumber = "OD-CANCEL-TEST-1";
        rawPassword = "secret";
        Order order = new Order(
            orderNumber,
            salesInfoId,
            1,
            10_000L,
            "홍길동",
            "010-1234-5678",
            passwordEncoder.encode(rawPassword),
            "테스트 상품",
            null,
            Instant.now().plusSeconds(900));
        orderRepository.save(order);
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    @RepeatedTest(3)
    void 동일_주문에_동시_취소_요청_2건은_한쪽만_성공() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicInteger otherFailureCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderService.cancelBeforePayment(orderNumber, rawPassword);
                    successCount.incrementAndGet();
                } catch (OrderCannotBeCancelledException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    otherFailureCount.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished).as("모든 스레드 10초 안에 완료").isTrue();
        assertThat(otherFailureCount.get()).as("예상치 못한 예외 없음").isZero();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // 주문 상태 CANCELLED, 재고 available 5 · reserved 0 (한 번만 복구)
        Order finalOrder = orderRepository.findByOrderNumber(orderNumber).orElseThrow();
        assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        SalesStock finalStock = salesStockRepository.findById(salesInfoId).orElseThrow();
        assertThat(finalStock.getAvailable()).isEqualTo(5);
        assertThat(finalStock.getReserved()).isZero();
    }

    @RepeatedTest(3)
    void 취소와_결제_시작_동시_요청시_정확히_한쪽만_성공() throws Exception {
        Long orderId = orderRepository.findByOrderNumber(orderNumber).orElseThrow().getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger cancelSuccess = new AtomicInteger();
        AtomicInteger paymentStartSuccess = new AtomicInteger();
        AtomicInteger otherFailure = new AtomicInteger();

        executor.submit(() -> {
            try {
                startGate.await();
                orderService.cancelBeforePayment(orderNumber, rawPassword);
                cancelSuccess.incrementAndGet();
            } catch (OrderCannotBeCancelledException e) {
                // OK 상대편 이겼음
            } catch (Exception e) {
                otherFailure.incrementAndGet();
            } finally {
                doneGate.countDown();
            }
        });
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        executor.submit(() -> {
            try {
                startGate.await();
                // 결제 시작 시뮬레이션: PENDING_PAYMENT → PAYMENT_CONFIRMING.
                // @Modifying 쿼리는 트랜잭션 필요. 서비스 우회 시 TransactionTemplate 로 감싼다.
                Integer updated = tt.execute(status -> orderRepository
                    .transitionStatus(orderId, "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
                if (updated != null && updated == 1) {
                    paymentStartSuccess.incrementAndGet();
                }
            } catch (Exception e) {
                otherFailure.incrementAndGet();
            } finally {
                doneGate.countDown();
            }
        });

        startGate.countDown();
        boolean finished = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(otherFailure.get()).isZero();
        // 정확히 한쪽만 성공
        assertThat(cancelSuccess.get() + paymentStartSuccess.get()).isEqualTo(1);
    }
}
