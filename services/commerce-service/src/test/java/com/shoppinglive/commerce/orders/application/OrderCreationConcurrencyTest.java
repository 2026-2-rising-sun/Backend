package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.application.InsufficientStockException;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * 주문 생성의 동시성 안전 검증.
 *
 * <p>P1 통합 완료 기준: "재고 5개에 수량 1개 주문 10건 동시 요청 → 주문 성공은 최대 5건이며
 * 재고는 음수가 되지 않는다".
 *
 * <p><b>이 테스트가 막는 사고:</b> 방어가 없으면 10 개 요청이 모두 "재고 5 개 있으니 주문
 * 가능" 이라고 판단한 뒤 각자 차감한다. 아무도 아직 줄이지 않은 시점에 다 같이 읽기 때문이다.
 * 결과는 주문 10 건에 재고 -5 이고, 5 명은 없는 물건을 결제하게 된다. {@code reserve} 의
 * {@code WHERE available >= :qty} 조건이 이 창을 닫는다.
 *
 * <p><b>테스트 격리:</b> {@code @SpringBootTest} 는 rollback 을 걸지 않으므로 각 테스트가 실제로
 * 커밋한다. {@link BeforeEach} 에서 명시적으로 청소한다.
 */
@SpringBootTest
class OrderCreationConcurrencyTest {

    private static final long PRODUCT_ID = 800L;
    private static final int INITIAL_STOCK = 5;
    private static final int THREADS = 10;

    @Autowired
    private OrderCreationService orderCreationService;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @Autowired
    private InMemoryShoppingClientStub shoppingClientStub;

    private Long salesId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
        shoppingClientStub.register(new ProductSnapshot(PRODUCT_ID, "한정 수량 상품", null));

        Sales sales = salesRepository.save(new Sales(PRODUCT_ID, 15_000L, SalesStatus.ON_SALE));
        salesId = sales.getId();
        salesStockRepository.save(new SalesStock(salesId, INITIAL_STOCK, 0));
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
    }

    private CreateOrderCommand command(int index) {
        return new CreateOrderCommand(
            PRODUCT_ID, 1, "구매자" + index, "010-0000-0000", "secret", null);
    }

    /**
     * 실패한 요청은 "재고 부족" 또는 "품절" 중 하나를 받는다. 두 가지가 되는 이유: 재고를 정확히
     * 소진시킨 주문이 판매 상태를 품절로 바꾸기 때문에, 그 뒤에 도착한 요청은 재고 조건에
     * 닿기도 전에 판매 상태에서 먼저 걸린다. 사용자에게는 둘 다 "지금 살 수 없다" 로 같다.
     */
    @RepeatedTest(3)
    void 재고_5에_10명이_동시_주문하면_5명만_성공하고_나머지는_주문_불가() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderCreationService.create(command(index), null);
                    successCount.incrementAndGet();
                } catch (InsufficientStockException | OrderNotAcceptableException e) {
                    rejectedCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean finished = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished).as("모든 스레드가 30초 안에 완료").isTrue();
        assertThat(unexpected)
            .as("주문 불가 외의 예외는 없어야 한다: %s", unexpected)
            .isEmpty();

        int succeeded = successCount.get();
        assertThat(succeeded + rejectedCount.get()).isEqualTo(THREADS);
        assertThat(succeeded)
            .as("성공은 재고 수를 넘지 못한다")
            .isLessThanOrEqualTo(INITIAL_STOCK);

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable())
            .as("판매 가능 재고는 음수가 되지 않는다")
            .isEqualTo(INITIAL_STOCK - succeeded)
            .isGreaterThanOrEqualTo(0);
        assertThat(stock.getReserved())
            .as("배정 수량은 성공한 주문 수와 같다")
            .isEqualTo(succeeded);

        // "주문만 저장되고 재고는 안 줄어듦" 또는 그 반대가 없어야 한다.
        List<Order> orders = orderRepository.findAll();
        assertThat(orders)
            .as("저장된 주문 수 = 성공 수 = 배정 수량")
            .hasSize(succeeded);
        assertThat(orders.stream().mapToInt(Order::getQuantity).sum())
            .isEqualTo(stock.getReserved());
    }

    /**
     * 재고를 정확히 소진하면 판매 상태가 품절로 바뀌어야 한다. 남아 있으면 공개 목록에
     * "판매 중" 으로 뜨는데 정작 아무도 못 사는 상태가 된다.
     */
    @RepeatedTest(3)
    void 재고가_모두_소진되면_품절로_전이된다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderCreationService.create(command(index), null);
                } catch (Exception ignored) {
                    // 재고 부족은 이 테스트의 관심사가 아니다.
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(salesStockRepository.findById(salesId).orElseThrow().getAvailable()).isZero();
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
            .isEqualTo(SalesStatus.SOLD_OUT);
    }

    /**
     * 같은 멱등키로 동시에 들어온 요청은 주문 한 건만 만들고 재고도 한 번만 잡아야 한다.
     * 사용자가 버튼을 연타하거나 네트워크 재전송이 겹치는 상황이다.
     */
    @RepeatedTest(3)
    void 같은_멱등키_동시_요청은_주문_한건만_만든다() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderCreationService.create(command(0), "same-key");
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(unexpected).as("모든 요청이 성공해야 한다: %s", unexpected).isEmpty();
        assertThat(orderRepository.findAll()).hasSize(1);

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getReserved()).as("재고도 한 번만 잡힌다").isEqualTo(1);
        assertThat(stock.getAvailable()).isEqualTo(INITIAL_STOCK - 1);
    }
}
