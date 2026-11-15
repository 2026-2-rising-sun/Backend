package com.shoppinglive.payment.payment.dto;

import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import java.time.Instant;

public record PaymentResponse(
        Long paymentId,
        PaymentStatus status,
        Long amount,
        Long refundedAmount,
        String pgOrderId,
        Instant approvedAt) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getStatus(),
                p.getAmount(),
                p.getRefundedAmount(),
                p.getPgOrderId(),
                p.getApprovedAt());
    }
}
