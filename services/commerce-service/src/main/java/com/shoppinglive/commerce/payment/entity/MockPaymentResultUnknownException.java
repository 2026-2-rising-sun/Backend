package com.shoppinglive.commerce.payment.entity;

/** 전송 실패만으로 최종 승인 결과를 판단할 수 없다. */
public class MockPaymentResultUnknownException extends RuntimeException {
    public enum Reason {
        RESPONSE_LOST_AFTER_RESULT,
        UNAVAILABLE_BEFORE_RESULT
    }

    private final Reason reason;

    public MockPaymentResultUnknownException(long attemptId, Reason reason) {
        super("Mock 결제 결과가 확인되지 않았습니다: attemptId=" + attemptId + ", reason=" + reason);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
