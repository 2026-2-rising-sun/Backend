package com.shoppinglive.commerce.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재고 배정 조건부 UPDATE ({@code SalesStockJpaRepository#reserve}) 검증.
 *
 * <p>주문 2 가 재고를 잡는 유일한 경로이므로, 주문 유스케이스를 얹기 전에 쿼리 자체의 안전을
 * 먼저 확인한다. 배정·복구·소진 세 쿼리가 재고 총량을 어떻게 보존하는지도 함께 본다.
 *
 * <p><b>테스트 격리:</b> {@code @SpringBootTest} 는 rollback 을 걸지 않으므로 각 테스트가 실제로
 * 커밋한다. {@link BeforeEach} 에서 명시적으로 청소한다.
 */
@SpringBootTest
class SalesStockReserveTest {

    private static final int INITIAL_AVAILABLE = 5;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long salesId;

    @BeforeEach
    void setUp() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(200L, 10_000L, SalesStatus.ON_SALE));
        salesId = sales.getId();
        salesStockRepository.save(new SalesStock(salesId, INITIAL_AVAILABLE, 0));
    }

    @AfterEach
    void tearDown() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    @Test
    @Transactional
    void reserve_성공하면_available은_줄고_reserved는_같은만큼_늘어난다() {
        int updated = salesStockRepository.reserve(salesId, 2);

        assertThat(updated).isEqualTo(1);
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(3);
        assertThat(stock.getReserved()).isEqualTo(2);
    }

    /**
     * 재고 총량은 배정으로 변하지 않는다. 소유만 available 에서 reserved 로 옮겨갈 뿐이라,
     * 관리자가 보는 "판매 가능 + 배정" 합계가 흔들리면 안 된다.
     */
    @Test
    @Transactional
    void reserve_해도_available과_reserved의_합은_보존된다() {
        salesStockRepository.reserve(salesId, 3);

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable() + stock.getReserved()).isEqualTo(INITIAL_AVAILABLE);
    }

    @Test
    @Transactional
    void reserve_available보다_많은_수량은_실패하고_재고는_그대로() {
        int updated = salesStockRepository.reserve(salesId, INITIAL_AVAILABLE + 1);

        assertThat(updated).isZero();
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(INITIAL_AVAILABLE);
        assertThat(stock.getReserved()).isZero();
    }

    /**
     * 경계값. 남은 재고를 정확히 다 가져가는 요청은 성공해야 한다.
     */
    @Test
    @Transactional
    void reserve_available과_정확히_같은_수량은_성공한다() {
        int updated = salesStockRepository.reserve(salesId, INITIAL_AVAILABLE);

        assertThat(updated).isEqualTo(1);
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isZero();
        assertThat(stock.getReserved()).isEqualTo(INITIAL_AVAILABLE);
    }

    @Test
    @Transactional
    void reserve_판매정보가_없으면_0을_반환한다() {
        assertThat(salesStockRepository.reserve(-1L, 1)).isZero();
    }

    /**
     * 주문 4 · 주문 5 · 결제 실패가 쓰는 복구 경로와 왕복이 맞는지 확인한다. 배정 후 복구하면
     * 원래 상태로 정확히 돌아와야 한다.
     */
    @Test
    @Transactional
    void reserve_후_restoreReserved하면_원래_재고로_돌아온다() {
        salesStockRepository.reserve(salesId, 2);
        int restored = salesStockRepository.restoreReserved(salesId, 2);

        assertThat(restored).isEqualTo(1);
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(INITIAL_AVAILABLE);
        assertThat(stock.getReserved()).isZero();
    }

    /**
     * 결제 성공 경로. 배정분이 소진되면 총량이 실제로 줄어든다 (판매 확정).
     */
    @Test
    @Transactional
    void reserve_후_consumeReserved하면_총량이_줄어든다() {
        salesStockRepository.reserve(salesId, 2);
        int consumed = salesStockRepository.consumeReserved(salesId, 2);

        assertThat(consumed).isEqualTo(1);
        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(3);
        assertThat(stock.getReserved()).isZero();
    }

    /**
     * P1 통합 완료 기준: "재고 5개에 수량 1개 주문 10건 동시 요청 → 성공 최대 5건, 재고 음수
     * 안 됨". 주문 유스케이스를 붙이기 전에 쿼리 계층에서 먼저 보장되는지 확인한다.
     */
    @RepeatedTest(3)
    void 재고_5에_수량_1_배정_10건_동시요청시_성공은_최대_5건() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicInteger otherFailureCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    // @Modifying 쿼리는 트랜잭션 없이는 실행되지 않는다. 실제 호출자인 주문 생성
                    // 유스케이스가 @Transactional 안에서 부르는 상황을 스레드마다 재현한다.
                    int updated = transactionTemplate
                        .execute(status -> salesStockRepository.reserve(salesId, 1));
                    if (updated == 1) {
                        successCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
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
        assertThat(successCount.get() + rejectedCount.get()).isEqualTo(threads);
        assertThat(successCount.get())
            .as("성공은 재고 수를 넘지 못한다")
            .isLessThanOrEqualTo(INITIAL_AVAILABLE);

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable())
            .as("available 은 음수가 되지 않고 성공 수만큼만 줄어든다")
            .isEqualTo(INITIAL_AVAILABLE - successCount.get())
            .isGreaterThanOrEqualTo(0);
        assertThat(stock.getReserved()).isEqualTo(successCount.get());
    }
}
