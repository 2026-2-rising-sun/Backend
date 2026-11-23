package com.shoppinglive.payment.refund.repository;

import com.shoppinglive.payment.refund.entity.RefundItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundItemRepository extends JpaRepository<RefundItem, Long> {
    List<RefundItem> findByRefundId(long refundId);
}
