package com.shoppinglive.commerce.shopping.application;

/**
 * shopping-service 를 일시적으로 사용할 수 없을 때 던지는 예외.
 *
 * <p>"상품이 없음"(404) 은 이 예외 대신 {@link java.util.Optional#empty()} 로 표현한다.
 * 이 예외는 네트워크·5xx·timeout 등 재시도로 회복 가능한 일시 장애만 나타낸다. Stub
 * 구현체와 실제 HTTP 구현체 모두 동일 시맨틱을 준수해야 계약 교체가 안전하다.
 */
public class ShoppingUnavailableException extends RuntimeException {

    public ShoppingUnavailableException(String message) {
        super(message);
    }

    public ShoppingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
