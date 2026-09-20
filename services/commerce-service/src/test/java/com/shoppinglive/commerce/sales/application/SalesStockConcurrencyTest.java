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
 * 판매 재고 조건부 UPDATE 의 동시성 안전을 검증한다.
 *
 * <p>P1 통합 완료 기준: "재고 5개 · 수량 1개 · 10건 동시 요청 → 성공 최대 5건, 재고 음수
 * 안 됨". 이 테스트가 그 규칙을 재고 계층에서 재현한다.
 *
 * <p><b>테스트 격리:</b> {@code @SpringBootTest} 는 트랜잭션 rollback 을 걸지 않으므로 각
 * 테스트는 실제 데이터를 커밋한다. {@link BeforeEach} 에서 명시적으로 청소하고 초기 상태를
 * 만든다.
 */
@SpringBootTest
class SalesStockConcurrencyTest {

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

        // productId 는 UNIQUE 라 고정 값 사용 (각 테스트 시작 전 데이터 모두 지우므로 안전).
        Sales sales = salesRepository.save(new Sales(100L, 10_000L, SalesStatus.READY));
        salesId = sales.getId();
        salesStockRepository.save(new SalesStock(salesId, 5, 0));
    }

    @AfterEach
    void tearDown() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    /**
     * 재고 5 개에 대해 delta = -1 요청 10 개가 동시에 몰리면 성공은 최대 5 건이며 최종
     * available 은 (5 - 성공 카운트) 이고 음수가 아니다.
     *
     * <p>{@link RepeatedTest} 3 회 반복으로 재현 가능성 확인.
     */
    @RepeatedTest(3)
    void 재고_5_동시_차감_10건_요청시_성공은_최대_5건() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicInteger otherFailureCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    salesService.adjustAvailable(salesId, -1);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException e) {
                    insufficientCount.incrementAndGet();
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

        assertThat(finished).as("모든 스레드가 10초 안에 완료").isTrue();
        assertThat(otherFailureCount.get()).as("예상치 못한 예외 없음").isZero();

        int total = successCount.get() + insufficientCount.get();
        assertThat(total).isEqualTo(threads);
        assertThat(successCount.get()).isLessThanOrEqualTo(5);

        SalesStock finalStock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(finalStock.getAvailable())
            .as("최종 available 은 음수가 아니고 5-성공 만큼 감소")
            .isEqualTo(5 - successCount.get())
            .isGreaterThanOrEqualTo(0);
    }
}
