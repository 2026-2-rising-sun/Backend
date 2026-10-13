package com.shoppinglive.shopping.coupon.application;

/** 쿠폰 목록을 확인하지 못함. 호출자는 이를 빈 목록으로 바꿔 응답한다 (Commerce 실패를 5xx 로 노출하지 않는다). */
public class ProductCouponUnavailableException extends RuntimeException {

    public ProductCouponUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
