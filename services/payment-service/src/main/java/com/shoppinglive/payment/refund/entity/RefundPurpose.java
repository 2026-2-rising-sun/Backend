package com.shoppinglive.payment.refund.entity;

/** 요청 목적. 고객 환불은 #370, 시스템 보상은 #386이 실행을 붙인다. */
public enum RefundPurpose {
    USER_REFUND,
    SYSTEM_COMPENSATION
}
