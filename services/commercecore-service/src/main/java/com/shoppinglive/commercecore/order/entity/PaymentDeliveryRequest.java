package com.shoppinglive.commercecore.order.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "payment_delivery_request")
public class PaymentDeliveryRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    private String requestId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_group_id")
    private Long paymentGroupId;

    @Column(name = "reference_number", nullable = false, length = 64)
    private String referenceNumber;

    @Column(nullable = false)
    private long amount;

    @Column(name = "request_key", nullable = false, length = 64)
    private String requestKey;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "core_refund_request_id", unique = true)
    private Long coreRefundRequestId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String command;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "next_action_at", nullable = false)
    private Instant nextActionAt;

    protected PaymentDeliveryRequest() {}
}
