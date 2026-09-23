package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.domain.SalesLookup;
import com.shoppinglive.commerce.sales.domain.SalesStatus;

/**
 * 서비스 간 판매정보 배치 조회 응답 DTO.
 *
 * <p>Shopping {@code CommerceSalesResponse} · Live {@code SalesSnapshot} 과 필드 이름·타입이
 * 같아야 한다. 이름을 바꾸거나 필드를 빼면 두 서비스가 동시에 깨지므로 계약 합의 없이 손대지
 * 않는다. 필드 추가는 호환된다 — 부르는 쪽은 모르는 필드를 무시한다.
 *
 * <p>{@code version} 을 담지 않는 이유: 낙관적 락 버전은 관리자 화면(판매 2)의 관심사이고,
 * 조회만 하는 다른 서비스에는 의미가 없다. 필요 없는 내부 값을 계약에 넣으면 나중에 못 뺀다.
 *
 * @param productId Shopping 상품 식별자
 * @param salesId 판매정보 식별자
 * @param price 판매가 (원)
 * @param status 판매 상태. 부르는 쪽은 모르는 값이 오면 추측하지 않고 조회 실패로 처리한다
 * @param available 주문 가능 재고 (결제 대기로 잡힌 수량 제외)
 */
public record SalesLookupResponse(
    Long productId,
    Long salesId,
    Long price,
    SalesStatus status,
    Integer available
) {

    public static SalesLookupResponse from(SalesLookup lookup) {
        return new SalesLookupResponse(
            lookup.productId(),
            lookup.salesId(),
            lookup.price(),
            lookup.status(),
            lookup.available()
        );
    }
}
