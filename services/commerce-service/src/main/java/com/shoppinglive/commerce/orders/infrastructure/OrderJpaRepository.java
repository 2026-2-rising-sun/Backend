package com.shoppinglive.commerce.orders.infrastructure;

import com.shoppinglive.commerce.orders.domain.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 JPA 리포지토리.
 *
 * <p>조회는 파생 쿼리, 상태 전이는 조건부 UPDATE 네이티브 쿼리로 처리한다.
 */
public interface OrderJpaRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * 주문을 결제 전 취소한다 (주문 4).
     *
     * <p>조건부 UPDATE `WHERE status = 'PENDING_PAYMENT'` 로 안전을 보장한다. 결제 진행 중이거나
     * 이미 확정·취소·만료된 주문은 이 UPDATE 로 갱신되지 않는다. {@code cancelled_at} 을 함께
     * 설정해 취소 시각을 감사에 남긴다.
     *
     * @return 0 이면 취소 불가 (이미 다른 상태), 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE orders "
                + "   SET status = 'CANCELLED', "
                + "       cancelled_at = CURRENT_TIMESTAMP, "
                + "       version = version + 1, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE id = :orderId "
                + "   AND status = 'PENDING_PAYMENT'",
        nativeQuery = true)
    int cancelOrder(@Param("orderId") Long orderId);

    /**
     * 주문 상태를 {@code expectedStatus} 에서 {@code nextStatus} 로 전이한다.
     *
     * <p>결제 시작 (PENDING_PAYMENT → PAYMENT_CONFIRMING) 등 도메인별 상태 전이의 범용 도구.
     * {@code cancelled_at} 은 세팅하지 않으므로 CANCELLED 전이에는 별도 {@link #cancelOrder}
     * 를 사용해야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE orders "
                + "   SET status = :nextStatus, "
                + "       version = version + 1, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE id = :orderId "
                + "   AND status = :expectedStatus",
        nativeQuery = true)
    int transitionStatus(
        @Param("orderId") Long orderId,
        @Param("expectedStatus") String expectedStatus,
        @Param("nextStatus") String nextStatus);
}
