package com.shoppinglive.payment.refund.repository;

import com.shoppinglive.payment.refund.entity.Refund;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByRefundRequestId(String refundRequestId);

    /**
     * 없을 때만 환불을 넣는다. 0이면 같은 refund_request_id가 이미 있거나, 같은 결제에 진행 중인 시스템 보상이 있다(부분 UNIQUE). null일 수
     * 있는 값은 CAST로 타입을 정한다(타입 없는 null 바인딩을 PostgreSQL이 bytea로 본다). 충돌 대상을 적지 않아 두 UNIQUE를 모두 받는다.
     * UNIQUE 예외를 잡지 않는 이유: PostgreSQL은 위반 시 트랜잭션 전체가 중단된다.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO refund (refund_request_id, execution_id, payment_id, purpose, amount,
                                        request_payload, status, failure_code, result_version,
                                        result_payload, created_at, updated_at)
                    VALUES (:refundRequestId, :executionId, CAST(:paymentId AS BIGINT), :purpose,
                            :amount, :requestPayload, :status, CAST(:failureCode AS VARCHAR(100)),
                            :resultVersion, CAST(:resultPayload AS TEXT), now(), now())
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("refundRequestId") String refundRequestId,
            @Param("executionId") UUID executionId,
            @Param("paymentId") Long paymentId,
            @Param("purpose") String purpose,
            @Param("amount") long amount,
            @Param("requestPayload") String requestPayload,
            @Param("status") String status,
            @Param("failureCode") String failureCode,
            @Param("resultVersion") int resultVersion,
            @Param("resultPayload") String resultPayload);
}
