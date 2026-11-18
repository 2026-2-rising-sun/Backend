package com.shoppinglive.payment.payment.dto;

import com.shoppinglive.payment.payment.entity.Payment;
import java.time.Instant;

public record CheckoutResponse(String pgOrderId, Long amount, Instant expiresAt) {
    public static CheckoutResponse from(Payment p) {
        return new CheckoutResponse(p.getPgOrderId(), p.getAmount(), p.getExpiresAt());
    }
}
