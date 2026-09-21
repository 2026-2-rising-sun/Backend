package com.shoppinglive.commerce.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
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

/**
 * 판매 상태 전이 조건부 UPDATE 의 동시성 안전을 검증한다.
 *
 * <p>두 관리자가 동시에 서로 다른 target 을 요청해도 조건부 UPDATE `WHERE status = :expected`
 * 로 한 명만 성공하고 나머지는 {@link ConcurrentStateChangeException} 을 받는다.
 */
@SpringBootTest
class SalesStatusConcurrencyTest {

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @Autowired
    private SalesService salesService;

    private Long salesId;

    @BeforeEach
    void setUp() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(101L, 10_000L, SalesStatus.READY));
        salesId = sales.getId();
        // PRIVATE→ON_SALE 자동 SOLD_OUT 분기를 피하기 위해 재고 여유
        salesStockRepository.save(new SalesStock(salesId, 5, 0));
    }

    @AfterEach
    void tearDown() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    /**
     * 두 관리자가 동시에 서로 다른 target 을 요청 → 한 명만 성공.
     */
    @RepeatedTest(3)
    void READY_에서_동시에_ON_SALE_과_PRIVATE_요청시_한_명만_성공() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicInteger otherFailureCount = new AtomicInteger();

        Runnable task1 = () -> {
            try {
                startGate.await();
                salesService.changeStatus(salesId, SalesStatus.ON_SALE);
                successCount.incrementAndGet();
            } catch (ConcurrentStateChangeException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                otherFailureCount.incrementAndGet();
            } finally {
                doneGate.countDown();
            }
        };
        Runnable task2 = () -> {
            try {
                startGate.await();
                salesService.changeStatus(salesId, SalesStatus.PRIVATE);
                successCount.incrementAndGet();
            } catch (ConcurrentStateChangeException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                otherFailureCount.incrementAndGet();
            } finally {
                doneGate.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);
        startGate.countDown();
        boolean finished = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished).as("두 스레드 모두 10초 안에 완료").isTrue();
        assertThat(otherFailureCount.get()).as("예상치 못한 예외 없음").isZero();
        // 두 요청 중 한 명 성공, 나머지 한 명 conflict
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // 최종 상태는 READY 가 아니고 ON_SALE 또는 PRIVATE 중 하나
        Sales finalSales = salesRepository.findById(salesId).orElseThrow();
        assertThat(finalSales.getStatus()).isIn(SalesStatus.ON_SALE, SalesStatus.PRIVATE);
    }
}
