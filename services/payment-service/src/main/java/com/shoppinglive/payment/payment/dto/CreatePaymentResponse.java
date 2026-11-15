package com.shoppinglive.payment.payment.dto;

import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import java.time.Instant;

/** pgOrderId는 승인 거절 뒤 비워질 수 있다(null이면 결제창을 다시 연다). */
public record CreatePaymentResponse(
        Long paymentId, PaymentStatus status, String pgOrderId, Long amount, Instant expiresAt) {
    public static CreatePaymentResponse from(Payment p) {
        return new CreatePaymentResponse(
                p.getId(), p.getStatus(), p.getPgOrderId(), p.getAmount(), p.getExpiresAt());
    }
}
