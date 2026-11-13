package com.shoppinglive.commercecore.order.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_payment_closure")
public class OrderPaymentClosure {
    @Id
    @Column(name = "closure_request_id", length = 36)
    private String closureRequestId;

    @Column(name = "payment_request_id", nullable = false, unique = true, length = 36)
    private String paymentRequestId;

    @Column(name = "payment_id", nullable = false, unique = true)
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_group_id", nullable = false)
    private Long paymentGroupId;

    @Column(name = "reference_number", nullable = false, length = 64)
    private String referenceNumber;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(nullable = false)
    private boolean acknowledged;

    @Column(name = "next_action_at", nullable = false)
    private Instant nextActionAt;

    protected OrderPaymentClosure() {}
}
