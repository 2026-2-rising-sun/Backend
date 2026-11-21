package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.PgCall;
import com.shoppinglive.payment.payment.entity.PgCallOutcome;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgCallRepository extends JpaRepository<PgCall, Long> {
    /** 결제의 마지막 승인 시도. 재호출도 같은 pg_order_id이므로 그중 가장 이른 시각이 첫 CONFIRM이다. */
    interface ConfirmAttempt {
        String getPgOrderId();

        Instant getFirstRequestedAt();
    }

    boolean existsByPaymentIdAndIdempotencyKeyAndOutcomeIn(
            Long paymentId, String idempotencyKey, Collection<PgCallOutcome> outcomes);

    @Query(
            """
            SELECT c.pgOrderId AS pgOrderId, MIN(c.requestedAt) AS firstRequestedAt
              FROM PgCall c
             WHERE c.paymentId = :paymentId AND c.kind = com.shoppinglive.payment.payment.entity.PgCallKind.CONFIRM
               AND c.pgOrderId = (SELECT l.pgOrderId FROM PgCall l WHERE l.id =
                   (SELECT MAX(m.id) FROM PgCall m WHERE m.paymentId = :paymentId
                       AND m.kind = com.shoppinglive.payment.payment.entity.PgCallKind.CONFIRM))
             GROUP BY c.pgOrderId
            """)
    Optional<ConfirmAttempt> findLatestConfirmAttempt(@Param("paymentId") long paymentId);

    /** 호출 전에 UNKNOWN으로 넣은 행에 결과를 한 번만 채운다. httpStatus는 응답을 받지 못했으면 null이다. */
    @Modifying
    @Query(
            value =
                    """
                    UPDATE pg_call
                       SET outcome = :outcome, result_code = :resultCode,
                           http_status = CAST(:httpStatus AS INTEGER), responded_at = now()
                     WHERE id = :id AND outcome = 'UNKNOWN'
                    """,
            nativeQuery = true)
    int recordResult(
            @Param("id") long id,
            @Param("outcome") String outcome,
            @Param("resultCode") String resultCode,
            @Param("httpStatus") Integer httpStatus);
}
