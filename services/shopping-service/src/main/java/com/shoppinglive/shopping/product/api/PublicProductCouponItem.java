package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.coupon.application.ProductCoupon;
import java.time.Instant;

/** 공개 쿠폰 목록 원소. Commerce 응답 필드를 그대로 옮기며, 할인 금액 계산은 주문 쪽 책임이다. */
public record PublicProductCouponItem(String id, String name, long fixedDiscount, boolean soldOut, Instant endsAt,
        Instant expiresAt) {

    static PublicProductCouponItem from(ProductCoupon coupon) {
        return new PublicProductCouponItem(coupon.id(), coupon.name(), coupon.fixedDiscount(), coupon.soldOut(),
            coupon.endsAt(), coupon.expiresAt());
    }
}
