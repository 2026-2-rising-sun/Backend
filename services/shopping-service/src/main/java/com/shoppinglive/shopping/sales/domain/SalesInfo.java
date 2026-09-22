package com.shoppinglive.shopping.sales.domain;

import java.util.Objects;

/**
 * 상품 1건의 판매정보 스냅샷. 화면 표시용으로 읽기만 하며, 가격·재고 확정은 주문 시점에 Commerce 가 한다.
 *
 * @param available 주문 가능 재고 (예약분 제외)
 */
public record SalesInfo(Long productId, Long salesId, long price, SalesStatus status, int available) {

    public SalesInfo {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(salesId, "salesId");
        Objects.requireNonNull(status, "status");
        if (price < 0 || available < 0) {
            throw new IllegalArgumentException("price/available must not be negative");
        }
    }
}
