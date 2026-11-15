package com.shoppinglive.payment.payment.entity;

/** 결제 상태. DB CHECK ck_payment_status와 같은 7개다. */
public enum PaymentStatus {
    CREATED,
    APPROVING,
    APPROVED,
    UNKNOWN,
    CANCELED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
