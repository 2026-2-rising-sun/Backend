package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderRef(String orderRef);

    /**
     * 없을 때만 CREATED 결제와 첫 결제창을 넣는다. 0이면 같은 orderRef가 이미 있다. UNIQUE 예외를 잡지 않는 이유: PostgreSQL은 위반 시
     * 트랜잭션 전체가 중단돼 기존 행을 읽을 수 없다. 네이티브 INSERT는 auditing을 거치지 않으므로 시각은 DB {@code now()}다.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO payment (order_ref, buyer_id, amount, refunded_amount, status,
                                         expires_at, pg_provider, pg_order_id, checkout_seq,
                                         created_at, updated_at)
                    VALUES (:orderRef, :buyerId, :amount, 0, 'CREATED',
                            :expiresAt, 'TOSS', :pgOrderId, 1, now(), now())
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("orderRef") String orderRef,
            @Param("buyerId") String buyerId,
            @Param("amount") long amount,
            @Param("expiresAt") Instant expiresAt,
            @Param("pgOrderId") String pgOrderId);

    /** CREATED이고 만료 전일 때만 결제창 회차를 올린다. SET의 {@code checkout_seq}는 바뀌기 전 값이다. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET checkout_seq = checkout_seq + 1,
                           pg_order_id  = order_ref || '-' || (checkout_seq + 1),
                           updated_at   = now()
                     WHERE order_ref = :orderRef AND status = 'CREATED' AND expires_at > now()
                    """,
            nativeQuery = true)
    int openCheckout(@Param("orderRef") String orderRef);

    /** ① 승인 시작. 검증 조건을 WHERE에 다시 걸어 동시 요청 중 한 건만 1행이 된다(PAY-CONFIRM-05). */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'APPROVING', approving_at = now(),
                           pg_payment_key = :paymentKey, updated_at = now()
                     WHERE id = :id AND status = 'CREATED' AND pg_order_id = :pgOrderId
                       AND amount = :amount AND buyer_id = :buyerId AND expires_at > now()
                    """,
            nativeQuery = true)
    int startApproval(
            @Param("id") long id,
            @Param("paymentKey") String paymentKey,
            @Param("pgOrderId") String pgOrderId,
            @Param("amount") long amount,
            @Param("buyerId") String buyerId);

    /** 승인 반영. 승인 시각이 없으면 DB 시각이다. 0행이면 다른 반영이 먼저 했거나 상태가 바뀌었다. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'APPROVED',
                           approved_at = COALESCE(CAST(:approvedAt AS TIMESTAMP WITH TIME ZONE), now()),
                           pg_status = :pgStatus, partial_cancelable = :partialCancelable,
                           updated_at = now()
                     WHERE id = :id AND status IN ('APPROVING', 'UNKNOWN')
                    """,
            nativeQuery = true)
    int approve(
            @Param("id") long id,
            @Param("approvedAt") Instant approvedAt,
            @Param("pgStatus") String pgStatus,
            @Param("partialCancelable") boolean partialCancelable);

    /** 첫 호출이 거절·보내지 않음이면 CREATED로 되돌린다. 같은 멱등 키 재사용을 막으려고 결제창(pg_order_id)을 비운다. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'CREATED', approving_at = NULL, pg_order_id = NULL, updated_at = now()
                     WHERE id = :id AND status = 'APPROVING'
                    """,
            nativeQuery = true)
    int revertToCreated(@Param("id") long id);

    /** 결과 불명. 10초 뒤 첫 조회, 조회 마감은 설정값(기본 30분) 뒤({@code UnknownPaymentPoller}가 처리). */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'UNKNOWN', next_check_at = now() + INTERVAL '10' SECOND,
                           check_deadline_at = now() + :deadlineSeconds * INTERVAL '1' SECOND,
                           updated_at = now()
                     WHERE id = :id AND status = 'APPROVING'
                    """,
            nativeQuery = true)
    int markUnknown(@Param("id") long id, @Param("deadlineSeconds") long deadlineSeconds);

    /** 보상 취소 전 선점. 이 UPDATE가 커밋되면 승인 반영(applyDone)이 0행이 된다. 승인·환불 상태는 건드리지 않는다. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'CANCELED', updated_at = now()
                     WHERE id = :id AND status IN ('APPROVING', 'UNKNOWN', 'CREATED')
                    """,
            nativeQuery = true)
    int markCanceled(@Param("id") long id);

    /**
     * 환불 반영. 승인된 결제이고 누적 환불액이 결제 금액을 넘지 않을 때만 더한다. 0이면 호출자가 다시 읽어 판단한다. SET의 {@code
     * refunded_amount}는 바뀌기 전 값이다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET refunded_amount = refunded_amount + :amount,
                           status = CASE WHEN refunded_amount + :amount = amount THEN 'REFUNDED'
                                         ELSE 'PARTIALLY_REFUNDED' END,
                           updated_at = now()
                     WHERE id = :id AND status IN ('APPROVED', 'PARTIALLY_REFUNDED')
                       AND refunded_amount + :amount <= amount
                    """,
            nativeQuery = true)
    int applyRefund(@Param("id") long id, @Param("amount") long amount);

    // ---- 복구·만료(#349). 아래 SQL은 PostgreSQL 전용이고 테스트도 @Tag("postgres")다 ----

    /** APPROVING 정체 → UNKNOWN, 바로 조회. 바깥 조건을 다시 걸어 동시 실행·새 승인 시도를 옮기지 않는다(PAY-RECOVER-01). */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'UNKNOWN', next_check_at = now(),
                           check_deadline_at = now() + :deadlineSeconds * INTERVAL '1' SECOND,
                           updated_at = now()
                     WHERE id IN (SELECT id FROM payment
                                   WHERE status = 'APPROVING'
                                     AND approving_at < now() - :stuckSeconds * INTERVAL '1' SECOND
                                   ORDER BY approving_at LIMIT :limit)
                       AND status = 'APPROVING'
                       AND approving_at < now() - :stuckSeconds * INTERVAL '1' SECOND
                    """,
            nativeQuery = true)
    int sweepStuckApproving(
            @Param("stuckSeconds") long stuckSeconds,
            @Param("deadlineSeconds") long deadlineSeconds,
            @Param("limit") int limit);

    /** 조회 차례가 된 UNKNOWN 결제. 마감이 지난 결제도 여기서 골라 조회기가 멈춘다. */
    @Query(
            value =
                    """
                    SELECT id FROM payment
                     WHERE status = 'UNKNOWN' AND next_check_at <= now()
                     ORDER BY next_check_at LIMIT :limit
                    """,
            nativeQuery = true)
    List<Long> findDueChecks(@Param("limit") int limit);

    /**
     * 마감 시각의 마지막 조회까지 끝났으면({@code next_check_at > check_deadline_at}, {@link #claimCheck} 참고) 조회
     * 대상에서 뺀다. 1행일 때만 ERROR 로그를 남겨 인스턴스가 여럿이어도 1번이다(PAY-RECOVER-04).
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment SET next_check_at = NULL, updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN'
                       AND next_check_at <= now() AND next_check_at > check_deadline_at
                    """,
            nativeQuery = true)
    int stopChecking(@Param("id") long id);

    /**
     * 이번 조회를 선점하며 다음 조회를 예약한다. 불명이 된 시각 t0 = 마감 − 설정 마감에서 지난 시간이 40초 미만이면 30초, 100초 미만이면 1분, 그 이상이면
     * 5분 뒤이고 마감을 넘기지 않는다(PAY-RECOVER-03). 마감 시각이 지나서 하는 조회는 마지막 조회다: 다음 시각을 마감 + 1초로 두어 다음 차례에
     * {@link #stopChecking}이 멈추게 한다. 토스를 부르기 전에 미뤄 두므로 다른 인스턴스는 0행이고, 조회 중 예외가 나도 같은 결제를 곧바로 다시 집지
     * 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET next_check_at = CASE
                               WHEN now() >= check_deadline_at THEN check_deadline_at + INTERVAL '1' SECOND
                               ELSE LEAST(
                                   now() + CASE
                                       WHEN now() - (check_deadline_at - :deadlineSeconds * INTERVAL '1' SECOND)
                                            < INTERVAL '40' SECOND THEN INTERVAL '30' SECOND
                                       WHEN now() - (check_deadline_at - :deadlineSeconds * INTERVAL '1' SECOND)
                                            < INTERVAL '100' SECOND THEN INTERVAL '1' MINUTE
                                       ELSE INTERVAL '5' MINUTE END,
                                   check_deadline_at) END,
                           updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN'
                       AND next_check_at <= now() AND next_check_at <= check_deadline_at
                    """,
            nativeQuery = true)
    int claimCheck(@Param("id") long id, @Param("deadlineSeconds") long deadlineSeconds);

    /** 조회 결과가 아직 확정이 아니면 마지막 토스 상태만 남긴다(마감 로그의 lastPgStatus). 0이면 이미 같은 상태였다. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment SET pg_status = :pgStatus, updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN' AND pg_status IS DISTINCT FROM :pgStatus
                    """,
            nativeQuery = true)
    int updateUnknownPgStatus(@Param("id") long id, @Param("pgStatus") String pgStatus);

    /** 조회 결과 ABORTED·EXPIRED → CREATED. revertToCreated와 같은 이유로 결제창을 비운다. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'CREATED', approving_at = NULL, pg_order_id = NULL,
                           next_check_at = NULL, check_deadline_at = NULL, pg_status = :pgStatus,
                           updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN'
                    """,
            nativeQuery = true)
    int revertUnknownToCreated(@Param("id") long id, @Param("pgStatus") String pgStatus);

    /** 조회 결과 CANCELED(전액 취소) → CANCELED. */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'CANCELED', pg_status = 'CANCELED',
                           next_check_at = NULL, check_deadline_at = NULL, updated_at = now()
                     WHERE id = :id AND status = 'UNKNOWN'
                    """,
            nativeQuery = true)
    int cancelUnknown(@Param("id") long id);

    /**
     * 만료 시각이 지난 CREATED → CANCELED. 승인 시작({@code expires_at > now()} 조건)과 한쪽만 1행이다(PAY-EXPIRE-01).
     */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment SET status = 'CANCELED', updated_at = now()
                     WHERE id IN (SELECT id FROM payment
                                   WHERE status = 'CREATED' AND expires_at < now()
                                   ORDER BY expires_at LIMIT :limit)
                       AND status = 'CREATED' AND expires_at < now()
                    """,
            nativeQuery = true)
    int expireCreated(@Param("limit") int limit);
}
