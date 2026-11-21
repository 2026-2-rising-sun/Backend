package com.shoppinglive.payment.refund.repository;

import com.shoppinglive.payment.refund.entity.RefundItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundItemRepository extends JpaRepository<RefundItem, Long> {
    List<RefundItem> findByRefundId(long refundId);

    /** 0이면 이 환불의 같은 판매자 주문 줄이 이미 있다. */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO refund_item (refund_id, seller_order_number, amount)
                    VALUES (:refundId, :sellerOrderNumber, :amount)
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("refundId") long refundId,
            @Param("sellerOrderNumber") String sellerOrderNumber,
            @Param("amount") long amount);
}
