package com.shoppinglive.payment.refund.event;

/** 다시 읽어도 해석할 수 없는 요청. 재시도 없이 DLT로 보낸다. */
public class InvalidRefundRequestException extends RuntimeException {
    public InvalidRefundRequestException(String message) {
        super(message);
    }

    public InvalidRefundRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
