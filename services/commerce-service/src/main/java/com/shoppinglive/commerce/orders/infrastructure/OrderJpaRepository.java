package com.shoppinglive.commerce.orders.infrastructure;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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
     * 멱등키로 기존 주문을 조회한다 (주문 2).
     *
     * <p>클라이언트가 {@code X-Idempotency-Key} 헤더로 같은 값을 재전송했을 때, 새 주문을 만드는
     * 대신 이미 만들어진 주문을 돌려주기 위해 사용한다. 완료 기준 "동일 주문 반복 요청은 주문
     * 한 건·재고 차감 한 번만 발생한다" 의 조회 경로다.
     *
     * <p>키가 {@code null} 인 요청도 허용하므로 (컬럼 nullable) 호출 전에 null 여부를 먼저
     * 가려야 한다. Postgres UNIQUE 는 NULL 중복을 허용해 제약으로는 걸러지지 않는다.
     */
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * 만료 대상 조회 (주문 5). status = PENDING_PAYMENT 이고 expires_at 이 기준 시각보다
     * 이전. 스케줄러가 한 번에 처리할 배치 크기는 {@code limit} 로 제한.
     *
     * <p>파생 쿼리로 `AND` 조합. `createdAt` 인덱스가 없어 정렬은 하지 않는다 (자연 순서 =
     * 생성 순서와 대체로 일치).
     */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant boundary, Limit limit);

    /**
     * 주문을 결제 전 취소한다 (주문 4).
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
     * 만료된 주문을 EXPIRED 로 전이한다 (주문 5). 조건부 UPDATE 로 중복 처리 방지.
     *
     * <p>{@code cancelled_at} 을 함께 세팅해 확정 시점을 감사에 남긴다 (P1 명세의
     * cancelled_at 은 CANCELLED 전용이지만 EXPIRED 도 같은 컬럼 재활용).
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE orders "
                + "   SET status = 'EXPIRED', "
                + "       cancelled_at = CURRENT_TIMESTAMP, "
                + "       version = version + 1, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE id = :orderId "
                + "   AND status = 'PENDING_PAYMENT' "
                + "   AND expires_at < CURRENT_TIMESTAMP",
        nativeQuery = true)
    int expireOrder(@Param("orderId") Long orderId);

    /**
     * 주문 상태를 {@code expectedStatus} 에서 {@code nextStatus} 로 전이한다.
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
