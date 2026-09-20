package com.shoppinglive.commerce.payments.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 결제 대상이 아닌 주문에 결제 시작 요청이 들어온 경우 (예: 이미 결제 진행 중·완료·취소·만료).
 *
 * <p>Spring 이 409 Conflict 로 매핑한다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class OrderNotEligibleForPaymentException extends RuntimeException {

    public OrderNotEligibleForPaymentException(String orderNumber) {
        super("order is not in PENDING_PAYMENT status: orderNumber=" + orderNumber);
    }
}
