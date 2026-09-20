package com.shoppinglive.commerce.payments.infrastructure;

import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 시도 JPA 리포지토리.
 *
 * <p>기본 CRUD + 조건부 UPDATE. Reconciler 스캔 쿼리는 결제 3 이슈에서 추가.
 */
public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {

    /**
     * PROCESSING · resolved_at IS NULL 인 결제 시도의 status 를 {@code outcome} 으로 확정한다.
     *
     * <p>Mock 엔진(결제 2)과 Reconciler(결제 3) 두 경로에서 동시 호출되어도 이 조건부 UPDATE
     * 로 하나만 성공. affected rows > 0 일 때만 후속 처리 (Order 전이 · 재고 처리) 트리거.
     *
     * @return 0 이면 이미 다른 흐름에서 확정됨 (idempotent no-op), 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE payment_attempt "
                + "   SET status = :outcome, "
                + "       resolved_at = CURRENT_TIMESTAMP, "
                + "       version = version + 1, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE id = :attemptId "
                + "   AND status = 'PROCESSING' "
                + "   AND resolved_at IS NULL",
        nativeQuery = true)
    int resolveIfProcessing(@Param("attemptId") Long attemptId, @Param("outcome") String outcome);
}
