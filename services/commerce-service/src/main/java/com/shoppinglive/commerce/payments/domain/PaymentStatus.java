package com.shoppinglive.commerce.payments.domain;

/**
 * 결제 시도 상태. P1 명세와 매핑.
 */
public enum PaymentStatus {
    /** DB/API 호환용 미사용 상태. 신규 결제는 PROCESSING으로 시작한다. */
    PENDING,
    /** 결제 처리 중. */
    PROCESSING,
    /** 승인 결과 미확정. 주문·재고·쿠폰 예약을 유지하고 같은 요청 결과를 확인한다. */
    UNKNOWN,
    /** 결제 성공 확정. */
    SUCCESS,
    /** 결제 실패 확정. */
    FAILED,
    /** 기존 데이터/API 호환용 terminal 값. 새 시간 초과 결과는 UNKNOWN으로 처리한다. */
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT;
    }

    public boolean isUnconfirmed() {
        return this == PROCESSING || this == UNKNOWN;
    }

    public boolean isConfirmedOutcome() {
        return this == SUCCESS || this == FAILED;
    }
}
