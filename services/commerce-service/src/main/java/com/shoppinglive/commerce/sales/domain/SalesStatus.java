package com.shoppinglive.commerce.sales.domain;

/**
 * 판매 상태. P1 명세 판매 4 완료 기준 표와 1:1 매핑.
 *
 * <p>실제 상태 전이 규칙(판매 준비 → 판매 중, 판매 중 → 품절 등)은 판매 4 이슈에서 별도
 * 추가한다. 여기서는 값 정의와 신규 주문 허용 여부만 표현한다.
 */
public enum SalesStatus {
    /** 필수 정보와 재고를 준비 중. 아직 공개·주문 불가. */
    READY,
    /** 판매 활성. 재고와 수량 검증을 거친 뒤 신규 주문 가능. */
    ON_SALE,
    /** 판매 가능한 재고가 0. 조회는 가능하지만 신규 주문 불가. */
    SOLD_OUT,
    /** 관리자 판단으로 숨김. 공개 목록·방송 상품 영역에서 제외되고 신규 주문 불가. */
    PRIVATE;

    /**
     * 이 상태에서 신규 주문을 받을 수 있는가.
     * <p>P1 명세: {@code ON_SALE} 만 신규 주문 허용. 이미 결제 대기 중인 주문은 이 판정과 무관하게 진행된다.
     */
    public boolean canAcceptNewOrder() {
        return this == ON_SALE;
    }
}
