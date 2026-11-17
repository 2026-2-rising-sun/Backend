package com.shoppinglive.commercecore.payment.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "late_approval_refund")
public class LateApprovalRefund {
    @Id
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "payment_group_id", nullable = false)
    private Long paymentGroupId;

    @Column(name = "refund_request_id", nullable = false, unique = true, length = 64)
    private String refundRequestId;

    @Column(name = "order_ref", nullable = false, length = 64)
    private String orderRef;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LateApprovalRefund() {}
}
