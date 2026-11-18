package com.shoppinglive.payment.payment.repository;

import com.shoppinglive.payment.payment.entity.PaymentSellerAmount;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentSellerAmountRepository extends JpaRepository<PaymentSellerAmount, Long> {
    List<PaymentSellerAmount> findByPaymentId(Long paymentId);

    /** 0이면 판매자 주문 번호가 이미 다른 결제에 있다. 예외 대신 영향 행 수로 판단한다. */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO payment_seller_amount
                        (payment_id, seller_order_number, seller_id, amount, commission_rate)
                    VALUES (:paymentId, :sellerOrderNumber, :sellerId, :amount, :commissionRate)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("paymentId") long paymentId,
            @Param("sellerOrderNumber") String sellerOrderNumber,
            @Param("sellerId") String sellerId,
            @Param("amount") long amount,
            @Param("commissionRate") BigDecimal commissionRate);
}
