package com.shoppinglive.commerce.sales.domain;

/**
 * 판매 상태. P1 명세 판매 4 완료 기준 표와 1:1 매핑.
 *
 * <p>상태 전이 규칙은 {@link #canTransitionTo(SalesStatus)} 에 도메인 메서드로 캡슐화된다.
 * 실제 전이는 서비스 계층의 조건부 UPDATE 로 수행되어 동시 요청 안전을 보장한다.
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
     *
     * <p>P1 명세: {@code ON_SALE} 만 신규 주문 허용. 이미 결제 대기 중인 주문은 이 판정과 무관하게 진행된다.
     */
    public boolean canAcceptNewOrder() {
        return this == ON_SALE;
    }

    /**
     * 이 상태에서 {@code target} 으로 전이할 수 있는가.
     *
     * <p>P1 명세 판매 4 전이표:
     * <pre>
     *   READY    → ON_SALE, PRIVATE
     *   ON_SALE  → PRIVATE, SOLD_OUT (SOLD_OUT 은 시스템 자동)
     *   SOLD_OUT → ON_SALE, PRIVATE
     *   PRIVATE  → ON_SALE, SOLD_OUT (재고 여부로 자동 결정)
     * </pre>
     */
    public boolean canTransitionTo(SalesStatus target) {
        return switch (this) {
            case READY -> target == ON_SALE || target == PRIVATE;
            case ON_SALE -> target == PRIVATE || target == SOLD_OUT;
            case SOLD_OUT -> target == ON_SALE || target == PRIVATE;
            case PRIVATE -> target == ON_SALE || target == SOLD_OUT;
        };
    }

    /**
     * 관리자 요청으로 직접 지정할 수 있는 상태인가.
     *
     * <p>{@code SOLD_OUT} 은 재고 0 진입 시 시스템 자동, {@code READY} 는 최초 생성(판매 1) 전용이라 둘 다 제외.
     */
    public boolean isAdminChangeable() {
        return this == ON_SALE || this == PRIVATE;
    }
}
