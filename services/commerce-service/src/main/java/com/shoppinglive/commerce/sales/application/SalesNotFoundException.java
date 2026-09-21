package com.shoppinglive.commerce.sales.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 판매정보를 찾을 수 없을 때 던지는 예외. Spring 이 자동으로 404 응답으로 매핑한다.
 *
 * <p>Sprint 2 후반에 {@code common-web} {@code GlobalExceptionHandler} 로 표준화된 오류 응답
 * 포맷을 붙일 때 이 애노테이션 대신 전용 매핑을 사용할 수 있다.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SalesNotFoundException extends RuntimeException {

    public SalesNotFoundException(String message) {
        super(message);
    }
}
