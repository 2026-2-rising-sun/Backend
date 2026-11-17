package com.shoppinglive.commercecore.payment.relay;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "payment_relay_refund")
class PaymentRelayRefund {
    @Id
    @Column(name = "refund_request_id", length = 64)
    private String refundRequestId;

    @Column(name = "payment_group_id", nullable = false)
    private Long paymentGroupId;

    @Column(name = "remote_payment_id", nullable = false, unique = true)
    private Long remotePaymentId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_code", columnDefinition = "TEXT")
    private String failureCode;

    protected PaymentRelayRefund() {}
}
