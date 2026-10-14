package com.shoppinglive.live.client.commerce;

/** Commerce 경계 DTO. price 는 KRW 정수, available 은 표시·안내 용도로만 쓴다. */
public record SalesSnapshot(
        Long productId, Long salesId, Long price, SalesStatus status, Integer available) {
    public SalesSnapshot {
        if (productId == null
                || productId < 1
                || salesId == null
                || salesId < 1
                || price == null
                || price < 0
                || status == null
                || available == null
                || available < 0) {
            throw new IllegalArgumentException("판매정보 스냅샷이 올바르지 않습니다.");
        }
    }
}
