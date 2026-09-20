package com.shoppinglive.commerce.orders.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 주문을 결제 전 취소하려 했으나 이미 다른 상태 (결제 확인 중·완료·실패·이미 취소·만료) 인 경우.
 *
 * <p>Spring 이 409 Conflict 로 매핑한다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class OrderCannotBeCancelledException extends RuntimeException {

    public OrderCannotBeCancelledException(String orderNumber) {
        super("order cannot be cancelled: orderNumber=" + orderNumber);
    }
}
