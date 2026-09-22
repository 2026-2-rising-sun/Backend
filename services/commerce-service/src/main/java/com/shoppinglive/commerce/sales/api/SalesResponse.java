package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;

/**
 * 판매정보 API 응답 DTO. 관리자 화면에 필요한 최소 필드만 노출한다.
 *
 * <p>{@code version} 은 현재 저장 버전이다. 가격 변경 API는 클라이언트 버전을 받지 않으며,
 * 겹치는 서버 트랜잭션의 충돌만 낙관적 락으로 검증한다.
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
