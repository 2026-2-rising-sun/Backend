package com.shoppinglive.payment.ledger.entity;

/** 분개를 만든 사건 종류. (종류, ID)마다 분개는 하나다. */
public enum LedgerRefType {
    PAYMENT_APPROVED,
    REFUND_SUCCEEDED,
    PURCHASE_CONFIRMED,
    SETTLEMENT_REVERSED,
    PAYOUT_COMPLETED
}
