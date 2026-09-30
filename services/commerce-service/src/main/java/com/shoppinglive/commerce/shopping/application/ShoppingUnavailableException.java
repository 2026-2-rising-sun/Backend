package com.shoppinglive.commerce.shopping.application;

/**
 * shopping-service 를 일시적으로 사용할 수 없을 때 던지는 예외.
 *
 * <p>"상품이 없음"(404) 은 이 예외 대신 {@link java.util.Optional#empty()} 로 표현한다.
 * 네트워크·5xx·timeout뿐 아니라 신뢰할 수 없는 응답 계약 위반도 이 예외로 나타낸다.
 * 응답 계약 위반과 404는 재시도하지 않는다.
 */
public class ShoppingUnavailableException extends RuntimeException {

    public ShoppingUnavailableException(String message) {
        super(message);
    }

    public ShoppingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
