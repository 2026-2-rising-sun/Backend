package com.shoppinglive.commerce.payments.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 결제 시도 조회 실패. 존재하지 않음 · 다른 주문 소유 모두 동일 404 로 통합 (leak 방지).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
