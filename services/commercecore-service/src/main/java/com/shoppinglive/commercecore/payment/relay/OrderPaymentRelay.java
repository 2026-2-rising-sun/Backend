package com.shoppinglive.commercecore.payment.relay;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "order_payment_relay")
class OrderPaymentRelay {
    @Id
    @Column(name = "payment_group_id")
    private Long paymentGroupId;

    @Column(name = "remote_payment_id", unique = true)
    private Long remotePaymentId;

    @Column(name = "pg_order_id", length = 64)
    private String pgOrderId;

    @Column(name = "payment_status", nullable = false, length = 32)
    private String paymentStatus;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected OrderPaymentRelay() {}
}
