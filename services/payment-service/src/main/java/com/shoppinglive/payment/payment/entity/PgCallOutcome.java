package com.shoppinglive.payment.payment.entity;

/** PG 호출 결과. 호출 전에는 UNKNOWN으로 넣는다. */
public enum PgCallOutcome {
    SUCCESS,
    DECLINED,
    NOT_SENT,
    UNKNOWN
}
