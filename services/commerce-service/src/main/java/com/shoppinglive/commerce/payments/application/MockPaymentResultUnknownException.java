package com.shoppinglive.commerce.payments.application;

/** A transport failure says nothing about the final authorization outcome. */
public class MockPaymentResultUnknownException extends RuntimeException {
    public enum Reason { RESPONSE_LOST_AFTER_RESULT, UNAVAILABLE_BEFORE_RESULT }

    private final Reason reason;

    public MockPaymentResultUnknownException(long attemptId, Reason reason) {
        super("Mock payment result is unconfirmed: attemptId=" + attemptId + ", reason=" + reason);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
