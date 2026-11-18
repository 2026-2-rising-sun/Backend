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
}
