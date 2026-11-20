package com.shoppinglive.payment.refund.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 환불 안의 판매자 주문별 금액. 역정산·판매자별 분개의 기준이고 실행(#370·#386)이 성공 결과와 함께 넣는다. */
@Entity
@Table(name = "refund_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false, updatable = false)
    private Long refundId;

    @Column(name = "seller_order_number", nullable = false, updatable = false, length = 64)
    private String sellerOrderNumber;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    public RefundItem(long refundId, String sellerOrderNumber, long amount) {
        this.refundId = refundId;
        this.sellerOrderNumber = sellerOrderNumber;
        this.amount = amount;
    }
}
