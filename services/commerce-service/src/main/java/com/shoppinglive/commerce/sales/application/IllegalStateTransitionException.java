package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.commerce.sales.domain.SalesStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 판매 상태 전이 규칙을 위반한 요청에 던지는 예외.
 *
 * <p>예: {@code ON_SALE} 에서 {@code READY} 로 되돌리려 하거나, 관리자가 {@code SOLD_OUT}
 * 을 직접 지정하려는 경우. Spring 이 400 Bad Request 로 매핑한다.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(SalesStatus current, SalesStatus target) {
        super("illegal state transition: " + current + " -> " + target);
    }

    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
