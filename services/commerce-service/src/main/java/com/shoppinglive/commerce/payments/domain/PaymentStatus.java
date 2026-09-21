package com.shoppinglive.commerce.payments.domain;

/**
 * 결제 시도 상태. P1 명세와 매핑.
 */
public enum PaymentStatus {
    /** 요청 접수 직후. 실제로는 결제 시작과 동시에 PROCESSING 으로 진입. */
    PENDING,
    /** Mock 처리 중. 확정 결과 대기. */
    PROCESSING,
    /** 결제 성공 확정. */
    SUCCESS,
    /** 결제 실패 확정. */
    FAILED,
    /** Reconciler 가 최대 대기 초과로 확정. */
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMEOUT;
    }
}
