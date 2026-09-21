package com.shoppinglive.shopping.sales.application;

/**
 * 판매정보를 일시적으로 알 수 없음 (네트워크·5xx·timeout·서킷 오픈·해석 불가 응답).
 * "판매정보 없음" 은 예외가 아니라 결과에서 빠지는 것으로 표현한다.
 */
public class SalesInfoUnavailableException extends RuntimeException {

    public SalesInfoUnavailableException(String message) {
        super(message);
    }

    public SalesInfoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
