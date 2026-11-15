package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.Payment;
import java.time.Instant;
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

    /** 결과 불명. 10초 뒤 첫 조회, 30분 뒤 조회 마감(#349가 처리). */
    @Modifying(clearAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE payment
                       SET status = 'UNKNOWN', next_check_at = now() + INTERVAL '10' SECOND,
                           check_deadline_at = now() + INTERVAL '30' MINUTE, updated_at = now()
                     WHERE id = :id AND status = 'APPROVING'
                    """,
            nativeQuery = true)
    int markUnknown(@Param("id") long id);

    /** 보상 취소 뒤. 승인·환불 상태는 건드리지 않는다. */
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
}
