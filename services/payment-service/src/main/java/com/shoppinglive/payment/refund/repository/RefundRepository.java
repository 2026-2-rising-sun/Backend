package com.shoppinglive.payment.refund.repository;

import com.shoppinglive.payment.refund.entity.Refund;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByRefundRequestId(String refundRequestId);

    /**
     * 없을 때만 환불을 넣는다. 0이면 같은 refund_request_id가 이미 있거나, 같은 결제에 진행 중인 시스템 보상이 있다(부분 UNIQUE). null일 수
     * 있는 값은 CAST로 타입을 정한다(타입 없는 null 바인딩을 PostgreSQL이 bytea로 본다). 충돌 대상을 적지 않아 두 UNIQUE를 모두 받는다.
     * UNIQUE 예외를 잡지 않는 이유: PostgreSQL은 위반 시 트랜잭션 전체가 중단된다. PROCESSING은 바로 재처리 대상(next_attempt_at =
     * now())으로 넣어, 커밋 뒤 실행 전에 죽어도 재처리가 집는다.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO refund (refund_request_id, execution_id, payment_id, purpose, amount,
                                        request_payload, status, failure_code, result_version,
                                        result_payload, refunded_amount, resolved_at, attempt_count,
                                        next_attempt_at, created_at, updated_at)
                    VALUES (:refundRequestId, :executionId, CAST(:paymentId AS BIGINT), :purpose,
                            :amount, :requestPayload, :status, CAST(:failureCode AS VARCHAR(100)),
                            :resultVersion, CAST(:resultPayload AS TEXT), 0,
                            CAST(:resolvedAt AS TIMESTAMP WITH TIME ZONE), 0,
                            CASE WHEN :status = 'PROCESSING' THEN now() END, now(), now())
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
            @Param("resultPayload") String resultPayload,
            @Param("resolvedAt") Instant resolvedAt);

    /** 재처리 대상: 이 목적의 PROCESSING·UNKNOWN 중 다음 처리 시각이 지난 것. 실행 PR의 스케줄러가 자기 목적만 집는다(REF-EXEC-01). */
    @Query(
            value =
                    """
                    SELECT * FROM refund
                     WHERE purpose = :purpose AND status IN ('PROCESSING','UNKNOWN')
                       AND next_attempt_at <= :now
                     ORDER BY next_attempt_at, id
                     LIMIT :limit
                    """,
            nativeQuery = true)
    List<Refund> findDue(
            @Param("purpose") String purpose, @Param("now") Instant now, @Param("limit") int limit);

    /**
     * 실행 선점. 다음 처리 시각이 지났을 때만 시도 횟수를 올리고 다음 처리 시각을 {@code leaseUntil}로 미룬다. 1이면 이 호출이 실행한다. 0이면 다른
     * 실행이 먼저 집었거나 이미 끝났다. 혼자 불리면 자기 트랜잭션으로 바로 커밋해, 토스 호출 전에 선점이 남는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund
                       SET attempt_count = attempt_count + 1, next_attempt_at = :leaseUntil,
                           updated_at = now()
                     WHERE id = :id AND status IN ('PROCESSING','UNKNOWN') AND next_attempt_at <= :now
                    """,
            nativeQuery = true)
    int claim(
            @Param("id") long id,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    /** 시스템 보상의 재시도 한도 알림을 한 번만 남기려고 그 시각을 기록한다. 보상은 UNKNOWN을 쓰지 않아 이 컬럼이 비어 있다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund SET check_deadline_at = :now, updated_at = now()
                     WHERE id = :id AND purpose = 'SYSTEM_COMPENSATION' AND status = 'PROCESSING'
                       AND check_deadline_at IS NULL
                    """,
            nativeQuery = true)
    int markCompensationOverdue(@Param("id") long id, @Param("now") Instant now);

    /** 이번 선점의 다음 처리 시각을 정한다. null이면 사람 확인까지 재처리하지 않는다. 0이면 다른 실행이 이미 집었거나 끝났다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund
                       SET next_attempt_at = CAST(:next AS TIMESTAMP WITH TIME ZONE), updated_at = now()
                     WHERE id = :id AND status = 'PROCESSING' AND attempt_count = :attemptCount
                    """,
            nativeQuery = true)
    int reschedule(
            @Param("id") long id,
            @Param("attemptCount") int attemptCount,
            @Param("next") Instant next);

    /**
     * 토스에 취소가 갔을 수 있는 상태로 표시한다. 조회 마감은 처음 한 번만 정한다. 0이면 이미 확정됐다. 혼자 불리면 자기 트랜잭션으로 커밋해 토스 호출 전에 남는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund
                       SET status = 'UNKNOWN',
                           check_deadline_at = COALESCE(check_deadline_at, :deadline),
                           updated_at = now()
                     WHERE id = :id AND status IN ('PROCESSING','UNKNOWN')
                    """,
            nativeQuery = true)
    int markUnknown(@Param("id") long id, @Param("deadline") Instant deadline);

    /**
     * 첫 취소가 토스에 가지 않았을 때 예약을 풀어 다음 실행이 다시 검증하게 한다. 조회 마감은 첫 시도 기준으로 유지한다. 그 사이 다른 실행이 선점했으면 ({@code
     * attempt_count}가 다르면) 그 실행의 예약이므로 풀지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund
                       SET status = 'PROCESSING', updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN' AND attempt_count = :attemptCount
                    """,
            nativeQuery = true)
    int releaseUnknown(@Param("id") long id, @Param("attemptCount") int attemptCount);

    /** 조회 마감이 지난 UNKNOWN을 재처리에서 뺀다. 1행일 때만 호출자가 알림 로그를 남겨 여러 인스턴스에서도 한 번이다. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund SET next_attempt_at = NULL, updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN' AND next_attempt_at IS NOT NULL
                       AND check_deadline_at <= :now
                    """,
            nativeQuery = true)
    int stopRetry(@Param("id") long id, @Param("now") Instant now);

    /** 한도를 차지하는 다른 환불: 성공, 토스에 갔을 수 있는 UNKNOWN, 전액을 돌려줄 진행 중 시스템 보상. 결제 행 잠금 안에서 읽는다. */
    @Query(
            value =
                    """
                    SELECT * FROM refund
                     WHERE payment_id = :paymentId AND id <> :id
                       AND (status IN ('SUCCEEDED','UNKNOWN')
                            OR (purpose = 'SYSTEM_COMPENSATION' AND status = 'PROCESSING'))
                    """,
            nativeQuery = true)
    List<Refund> findHoldingLimit(@Param("paymentId") long paymentId, @Param("id") long id);

    /**
     * 결과 기록. 읽은 상태·버전 그대로이고 허용된 전이일 때만 바꾼다. FAILED 뒤 변경은 돈이 움직였을 수 있는 시스템 보상만 가능하고, 접수에서 실패한 행은 토스를
     * 부른 적이 없어 제외한다(REF-LATE-02). 0이면 바꿀 수 없거나 다른 반영이 먼저 했다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE refund
                       SET status = :status, result_version = :version, result_payload = :resultPayload,
                           refunded_amount = :refundedAmount, resolved_at = :resolvedAt,
                           failure_code = CAST(:failureCode AS VARCHAR(100)),
                           pg_transaction_key = COALESCE(CAST(:pgTransactionKey AS VARCHAR(64)),
                                                         pg_transaction_key),
                           next_attempt_at = NULL, updated_at = now()
                     WHERE id = :id AND status = :previousStatus AND result_version = :previousVersion
                       AND (status IN ('PROCESSING', 'UNKNOWN')
                            OR (status = 'FAILED'
                                AND purpose = 'SYSTEM_COMPENSATION'
                                AND :status IN ('SUCCEEDED', 'NO_CHARGE')
                                AND payment_id IS NOT NULL
                                AND failure_code NOT IN ('PAYMENT_NOT_FOUND', 'PAYMENT_ID_REQUIRED',
                                                         'PAYMENT_MISMATCH', 'COMPENSATION_IN_PROGRESS')))
                    """,
            nativeQuery = true)
    int recordResult(
            @Param("id") long id,
            @Param("previousStatus") String previousStatus,
            @Param("previousVersion") int previousVersion,
            @Param("status") String status,
            @Param("version") int version,
            @Param("resultPayload") String resultPayload,
            @Param("refundedAmount") long refundedAmount,
            @Param("resolvedAt") Instant resolvedAt,
            @Param("failureCode") String failureCode,
            @Param("pgTransactionKey") String pgTransactionKey);
}
