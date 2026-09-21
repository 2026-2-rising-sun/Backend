package com.shoppinglive.commerce.payments.infrastructure;

import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 시도 JPA 리포지토리.
 */
public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {

    /**
     * Reconciler 스캔용 (결제 3). status = PROCESSING · scheduled_resolve_at 이 기준 시각 이전.
     */
    List<PaymentAttempt> findByStatusAndScheduledResolveAtBefore(
        PaymentStatus status, Instant boundary, Limit limit);

    /**
     * PROCESSING · resolved_at IS NULL 인 결제 시도의 status 를 확정한다.
     *
     * <p>Mock 엔진 · Reconciler 두 경로에서 동시 호출되어도 조건부 UPDATE 로 하나만 성공.
     *
     * @return 0 이면 이미 확정됨, 1 이면 성공
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
