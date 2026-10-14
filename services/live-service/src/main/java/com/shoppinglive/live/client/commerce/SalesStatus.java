package com.shoppinglive.live.client.commerce;

public enum SalesStatus {
    ON_SALE,
    SOLD_OUT,
    READY,
    PRIVATE;

    /** 방송에 연결·공개할 수 있는 상태. available 재고는 판단에 쓰지 않는다. */
    public boolean broadcastable() {
        return this == ON_SALE || this == SOLD_OUT;
    }
}
