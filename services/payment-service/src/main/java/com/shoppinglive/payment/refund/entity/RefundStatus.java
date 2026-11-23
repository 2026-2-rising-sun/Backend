package com.shoppinglive.payment.refund.entity;

/** 환불 상태. PROCESSING·UNKNOWN은 진행 중이고, 나머지는 결과를 보낸 확정 상태다. UNKNOWN 결과는 보내지 않는다. */
public enum RefundStatus {
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    NO_CHARGE
}
