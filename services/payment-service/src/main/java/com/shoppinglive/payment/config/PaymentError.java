package com.shoppinglive.payment.config;

import org.springframework.http.HttpStatus;

/** Payment 업무 오류 코드. 공통 {@code ErrorCode}에 없는 코드를 응답 {@code error.code}로 그대로 쓴다. */
public enum PaymentError {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INVALID_ORDER_REF(HttpStatus.BAD_REQUEST, "orderRef 형식이 잘못되었습니다."),
    INVALID_EXPIRES_AT(HttpStatus.BAD_REQUEST, "결제 만료 시각이 현재 이후가 아닙니다."),
    SUM_MISMATCH(HttpStatus.BAD_REQUEST, "판매자별 금액 합이 결제 금액과 다릅니다."),
    NOT_READY(HttpStatus.NOT_FOUND, "결제가 아직 생성되지 않았습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제를 찾을 수 없습니다."),
    AMOUNT_CONFLICT(HttpStatus.CONFLICT, "같은 주문 참조에 금액이나 구매자가 다른 결제가 있습니다."),
    PAYMENT_CLOSED(HttpStatus.CONFLICT, "결제가 닫혔습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    PaymentError(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
