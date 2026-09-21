package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;

/**
 * 판매정보 API 응답 DTO. 관리자 화면에 필요한 최소 필드만 노출한다.
 *
 * <p>{@code version} 은 클라이언트가 다음 수정 요청 시 낙관적 락 재검증에 활용할 수 있다.
 */
public record SalesResponse(
    Long id,
    Long productId,
    Long price,
    SalesStatus status,
    Long version
) {

    public static SalesResponse from(Sales sales) {
        return new SalesResponse(
            sales.getId(),
            sales.getProductId(),
            sales.getPrice(),
            sales.getStatus(),
            sales.getVersion()
        );
    }
}
