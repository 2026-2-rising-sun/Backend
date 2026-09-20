package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미결제 주문 만료 스케줄러 (주문 5, 보완 제안).
 *
 * <p>{@code commerce.order.expiration.duration} 이 지난 결제 전 주문을 자동 만료 처리한다.
 * 60 초마다 실행. 초기 지연 60 초 (테스트 컨텍스트에서 자동 실행되어 데이터 간섭 방지).
 *
 * <p>처리 단위: 한 번에 최대 {@link #BATCH_SIZE} 건. 대량 밀림 시에도 트랜잭션이 지나치게
 * 길어지지 않도록 제한.
 */
@Component
public class OrderExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final OrderJpaRepository orderRepository;
    private final SalesStockJpaRepository salesStockRepository;

    public OrderExpirationScheduler(
        OrderJpaRepository orderRepository, SalesStockJpaRepository salesStockRepository) {
        this.orderRepository = orderRepository;
        this.salesStockRepository = salesStockRepository;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void runScheduled() {
        int processed = processExpiredOrders();
        if (processed > 0) {
            log.info("expired {} pending orders", processed);
        }
    }

    /**
     * 만료 대상을 조회해 EXPIRED 전이 · 재고 복구 처리한다. 테스트에서 직접 호출 가능하도록
     * public.
     *
     * @return 실제로 만료 처리된 주문 수
     */
    @Transactional
    public int processExpiredOrders() {
        List<Order> candidates = orderRepository.findByStatusAndExpiresAtBefore(
            OrderStatus.PENDING_PAYMENT, Instant.now(), Limit.of(BATCH_SIZE));

        int expired = 0;
        for (Order order : candidates) {
            int updated = orderRepository.expireOrder(order.getId());
            if (updated == 1) {
                int restored = salesStockRepository
                    .restoreReserved(order.getSalesInfoId(), order.getQuantity());
                if (restored == 0) {
                    log.warn(
                        "stock reserved insufficient during expiration: orderNumber={}",
                        order.getOrderNumber());
                }
                expired++;
            }
            // updated == 0 이면 이미 다른 흐름에서 상태 전이 (결제 시작·수동 취소 등). 무해.
        }

        return expired;
    }
}
