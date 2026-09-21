package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.domain.SalesStock;

/**
 * 재고 API 응답 DTO.
 *
 * <p>{@code available} 은 지금 살 수 있는 수량, {@code reserved} 는 이미 주문에 배정되어
 * 결제 대기 중인 수량. 관리자는 이 둘의 구분을 이해하고 재고 계획에 활용한다.
 */
public record SalesStockResponse(
    Long salesInfoId,
    Integer available,
    Integer reserved
) {

    public static SalesStockResponse from(SalesStock stock) {
        return new SalesStockResponse(
            stock.getSalesInfoId(), stock.getAvailable(), stock.getReserved());
    }
}
