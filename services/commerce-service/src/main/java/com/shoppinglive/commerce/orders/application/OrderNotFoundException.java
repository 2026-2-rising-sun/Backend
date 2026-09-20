package com.shoppinglive.commerce.orders.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 주문 조회 실패 예외.
 *
 * <p>실제 원인은 두 가지가 있지만 (존재하지 않는 주문번호 · 비밀번호 불일치) 응답은
 * 동일하게 404 Not Found 로 통합한다. 존재 여부 · 비밀번호 정합성의 leak 을 방지하기 위함.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
