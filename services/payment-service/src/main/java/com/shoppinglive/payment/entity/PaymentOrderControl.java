package com.shoppinglive.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** 종료 통지가 결제 생성보다 먼저 도착해도 동일한 실행 키를 보존한다. */
@Entity
@Table(name = "payment_order_control")
public class PaymentOrderControl {
    @Id
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "payment_request_id", nullable = false, unique = true, length = 36)
    private String paymentRequestId;

    @Column(name = "closure_request_id", unique = true, length = 36)
    private String closureRequestId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "payment_group_id")
    private Long paymentGroupId;

    @Column(name = "reference_number", length = 64)
    private String referenceNumber;

    private Long amount;

    @Column(length = 32)
    private String reason;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected PaymentOrderControl() {}
}
