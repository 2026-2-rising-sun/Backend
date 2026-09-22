package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 판매정보를 찾을 수 없을 때 던지는 예외 (404).
 *
 * <p>원래 이 클래스는 {@code @ResponseStatus(NOT_FOUND)} 만 붙인 {@code RuntimeException} 이었다.
 * 그런데 {@code common-web} 의 {@code GlobalExceptionHandler} 에
 * {@code @ExceptionHandler(Exception.class)} 가 있고, Spring 은
 * {@code ExceptionHandlerExceptionResolver} 를 {@code ResponseStatusExceptionResolver} 보다 먼저
 * 적용한다. 그래서 {@code @ResponseStatus} 는 무시되고 실제 응답은 500 이었다.
 * {@link BusinessException} 을 상속하면 핸들러가 {@link ErrorCode} 를 보고 404 로 내려준다.
 *
 * <p>같은 문제가 남아 있는 예외가 이 서비스에 더 있다 ({@code InsufficientStockException},
 * {@code OrderNotFoundException} 등). 일괄 정리는 별도 이슈에서 다룬다.
 */
public class SalesNotFoundException extends BusinessException {

    public SalesNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
