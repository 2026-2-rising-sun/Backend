package com.shoppinglive.shopping.coupon.event;

/** contracts/events/commerce-coupon-info.yaml에 정의된 소비측 전송 상수. */
public final class CouponInfoEventContract {
    public static final String TOPIC_NAME = "commerce.coupon-info.v1";
    public static final int SCHEMA_VERSION = 1;

    private CouponInfoEventContract() {}
}
