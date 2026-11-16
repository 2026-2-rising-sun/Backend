package com.shoppinglive.commercecore.payment.relay;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "seller_order")
class SellerOrderSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_group_id", nullable = false)
    private Long paymentGroupId;

    @Column(name = "seller_order_number", nullable = false, unique = true, length = 64)
    private String sellerOrderNumber;

    @Column(name = "seller_id", nullable = false, length = 36)
    private String sellerId;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "payable_amount", nullable = false)
    private long payableAmount;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    protected SellerOrderSnapshot() {}
}
