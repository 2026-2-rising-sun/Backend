package com.shoppinglive.payment.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** 결제 안의 판매자별 금액. 부분 환불과 정산의 기준이며 생성 뒤 바꾸지 않는다. */
@Entity
@Table(name = "payment_seller_amount")
public class PaymentSellerAmount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "seller_order_number", nullable = false, updatable = false, length = 64)
    private String sellerOrderNumber;

    @Column(name = "seller_id", nullable = false, updatable = false, length = 36)
    private String sellerId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Column(name = "commission_rate", nullable = false, updatable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    protected PaymentSellerAmount() {
        // JPA 전용
    }

    public PaymentSellerAmount(
            Long paymentId,
            String sellerOrderNumber,
            String sellerId,
            long amount,
            BigDecimal commissionRate) {
        this.paymentId = paymentId;
        this.sellerOrderNumber = sellerOrderNumber;
        this.sellerId = sellerId;
        this.amount = amount;
        this.commissionRate = commissionRate;
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getSellerOrderNumber() {
        return sellerOrderNumber;
    }

    public String getSellerId() {
        return sellerId;
    }

    public Long getAmount() {
        return amount;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }
}
