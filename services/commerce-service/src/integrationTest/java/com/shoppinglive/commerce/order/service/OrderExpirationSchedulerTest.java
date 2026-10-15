package com.shoppinglive.commerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 주문 만료 스케줄러 통합 테스트.
 *
 * <p>{@code processExpiredOrders()} 를 직접 호출해 검증 (스케줄러가 자동 실행되지 않는 동안). 만료 대상만 EXPIRED 로 전이되는지, 다른
 * 상태의 주문은 건드리지 않는지, 재고가 정확히 한 번만 복구되는지 확인.
 */
@SpringBootTest
class OrderExpirationSchedulerTest
        extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {

    @Autowired private OrderExpirationScheduler scheduler;

    @Autowired private OrderJpaRepository orderRepository;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    private Long salesInfoId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();

        Sales sales = salesRepository.save(new Sales(300L, 10_000L, SalesStatus.ON_SALE));
        salesInfoId = sales.getId();
        // available=3, reserved=2 (아래 2 개의 주문에 배정된 것으로 가정)
        salesStockRepository.save(new SalesStock(salesInfoId, 3, 2));
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    private Order buildOrder(String orderNumber, int qty, Instant expiresAt) {
        return new Order(
                orderNumber,
                salesInfoId,
                qty,
                10_000L,
                "홍길동",
                "010-1234-5678",
                "11111111-1111-4111-8111-111111111111",
                "테스트 상품",
                null,
                expiresAt);
    }

    @DisplayName("기한이 지난 PENDING 주문만 EXPIRED로 변경하고 재고를 복구한다")
    @Test
    void expiresOnlyOverduePendingOrdersAndRestoresStock() {
        Order expired =
                orderRepository.save(buildOrder("OD-EXPIRED-1", 1, Instant.now().minusSeconds(60)));
        Order stillPending =
                orderRepository.save(
                        buildOrder("OD-STILL-PENDING", 1, Instant.now().plusSeconds(600)));

        int processed = scheduler.processExpiredOrders();

        assertThat(processed).isEqualTo(1);

        Order refreshedExpired = orderRepository.findById(expired.getId()).orElseThrow();
        assertThat(refreshedExpired.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(refreshedExpired.getCancelledAt()).isNotNull();

        Order refreshedPending = orderRepository.findById(stillPending.getId()).orElseThrow();
        assertThat(refreshedPending.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(refreshedPending.getCancelledAt()).isNull();

        // 재고: reserved 2 → 1 (한 개만 복구), available 3 → 4
        SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(4);
        assertThat(stock.getReserved()).isEqualTo(1);
    }

    @DisplayName("스케줄러를 두 번 실행해도 재고를 중복 복구하지 않는다")
    @Test
    void doesNotRestoreStockTwiceAcrossSchedulerRuns() {
        orderRepository.save(buildOrder("OD-EXPIRED-2", 1, Instant.now().minusSeconds(60)));

        scheduler.processExpiredOrders();
        int secondRun = scheduler.processExpiredOrders();

        assertThat(secondRun).isZero();

        SalesStock stock = salesStockRepository.findById(salesInfoId).orElseThrow();
        // 첫 번째 실행에서만 available += 1, reserved -= 1. 두 번째는 no-op
        assertThat(stock.getAvailable()).isEqualTo(4);
        assertThat(stock.getReserved()).isEqualTo(1);
    }
}
