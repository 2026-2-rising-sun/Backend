package com.shoppinglive.payment.config;

public class PaymentException extends RuntimeException {
    private final PaymentError error;

    public PaymentException(PaymentError error) {
        this(error, error.message());
    }

    public PaymentException(PaymentError error, String message) {
        super(message);
        this.error = error;
    }

    public PaymentError error() {
        return error;
    }
}
