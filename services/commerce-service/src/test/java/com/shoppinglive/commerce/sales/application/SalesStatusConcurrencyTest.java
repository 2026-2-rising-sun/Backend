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
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

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
     * 두 관리자가 동시에 서로 다른 target 을 요청해도 상태가 깨지지 않는다.
     *
     * <p><b>왜 "한 명만 성공" 을 단정하지 않는가:</b> 스레드를 같은 순간에 풀어줘도 두 요청이
     * 실제로 겹친다는 보장은 없다. 첫 요청이 {@code READY → ON_SALE} 을 커밋한 뒤에 두 번째
     * 요청이 읽기 시작하면, 두 번째는 {@code ON_SALE} 을 읽고 {@code ON_SALE → PRIVATE} 로
     * 정상 전이한다. 이것도 옳은 동작이다 — 동시가 아니라 순차로 실행된 것뿐이다.
     *
     * <p>예전에는 성공 수가 정확히 1 이라고 단정했는데, 코어가 적은 CI 러너에서 두 스레드가
     * 순차 실행되면서 성공 수가 2 가 되어 빌드가 깨졌다. 스케줄링에 기대는 단정이었다.
     * 여기서는 어느 쪽으로 실행되든 성립하는 것만 확인하고, 겹쳤을 때의 보장은
     * {@link #겹침을_강제하면_조건부_UPDATE_는_한_쪽만_통과시킨다()} 가 결정적으로 검증한다.
     */
    @RepeatedTest(3)
    void READY_에서_동시에_ON_SALE_과_PRIVATE_요청해도_상태가_깨지지_않는다() throws Exception {
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

        // 겹쳤으면 성공 1 · 충돌 1, 순차로 실행됐으면 성공 2. 어느 쪽이든 요청은 두 건 모두
        // 결론이 나야 하고, 적어도 한 건은 성공해야 한다.
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(2);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

        // 무엇보다 상태가 READY 에 머무르거나 알 수 없는 값이 되면 안 된다.
        Sales finalSales = salesRepository.findById(salesId).orElseThrow();
        assertThat(finalSales.getStatus()).isIn(SalesStatus.ON_SALE, SalesStatus.PRIVATE);
    }

    /**
     * 조건부 UPDATE {@code WHERE status = :expected} 가 compare-and-swap 으로 동작하는지를
     * 결정적으로 검증한다.
     *
     * <p>두 트랜잭션이 모두 {@code READY} 를 읽은 것을 래치로 보장한 뒤에야 UPDATE 를 보낸다.
     * 이렇게 겹침을 강제하면 스레드 스케줄링과 무관하게 항상 한 쪽만 통과해야 한다. 서비스
     * 계층({@code changeStatus})은 읽기와 UPDATE 사이에 끼어들 틈을 주지 않으므로 리포지토리
     * 계층에서 직접 확인한다.
     */
    @RepeatedTest(3)
    void 겹침을_강제하면_조건부_UPDATE_는_한_쪽만_통과시킨다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();

        for (String target : new String[] {"ON_SALE", "PRIVATE"}) {
            executor.submit(() -> {
                try {
                    Integer updated = transactionTemplate.execute(status -> {
                        // 두 트랜잭션이 모두 READY 를 관측한 뒤에 UPDATE 를 보내게 한다.
                        salesRepository.findById(salesId).orElseThrow().getStatus();
                        bothRead.countDown();
                        awaitQuietly(bothRead);
                        return salesRepository.transitionStatus(salesId, "READY", target);
                    });
                    if (updated != null && updated > 0) {
                        winners.incrementAndGet();
                    } else {
                        losers.incrementAndGet();
                    }
                } finally {
                    doneGate.countDown();
                }
            });
        }

        assertThat(doneGate.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(winners.get()).as("READY 에서 출발한 전이는 한 건만 성공").isEqualTo(1);
        assertThat(losers.get()).as("나머지는 대상 0 행으로 밀려난다").isEqualTo(1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
            .isIn(SalesStatus.ON_SALE, SalesStatus.PRIVATE);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
