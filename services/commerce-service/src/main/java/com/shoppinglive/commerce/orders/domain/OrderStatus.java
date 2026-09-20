package com.shoppinglive.commerce.orders.domain;

/**
 * 주문 상태. P1 명세 "주문 상태와 허용 동작" 표와 1:1 매핑.
 */
public enum OrderStatus {
    /** 주문 생성·주문번호 발급·재고 확보 완료. 결제 시작·취소·만료(제안 채택 시) 가능. */
    PENDING_PAYMENT,
    /** 결제 처리 중이거나 결과 미확정. 확보 재고 유지. */
    PAYMENT_CONFIRMING,
    /** 결제 성공 확정. 추가 재고 차감 없음. */
    PAID,
    /** 결제 실패 확정. 확보 재고 반환됨. */
    FAILED,
    /** 결제 전 사용자 취소. 확보 재고 반환됨. */
    CANCELLED,
    /** 미결제 만료. 확보 재고 반환됨. (주문 5 채택 시) */
    EXPIRED;

    /**
     * 결제 흐름의 최종 확정 상태인가.
     * PAID · FAILED · CANCELLED · EXPIRED 는 더 이상 상태 변경 없음.
     */
    public boolean isTerminal() {
        return this == PAID || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
