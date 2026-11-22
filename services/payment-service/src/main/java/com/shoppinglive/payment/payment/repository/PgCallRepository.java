package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.PgCall;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PgCallRepository extends JpaRepository<PgCall, Long> {
    long countByPaymentIdAndKindAndIdempotencyKey(
            Long paymentId, PgCallKind kind, String idempotencyKey);

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
