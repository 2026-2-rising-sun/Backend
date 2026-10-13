package com.shoppinglive.shopping.coupon.infrastructure;

import com.shoppinglive.shopping.coupon.application.ProductCoupon;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Commerce 쿠폰 원소 (제안 계약). 래퍼 타입으로 받아 필드가 빠진 응답을 변환 단계에서 실패로 드러낸다.
 * 값을 기본값으로 채우면 없는 할인이 보이거나 품절 쿠폰이 구매 가능하게 보일 수 있다.
 */
record CommerceCouponResponse(String id, String name, Long fixedDiscount, Boolean soldOut, Instant endsAt,
        Instant expiresAt) {

    ProductCoupon toCoupon() {
        String couponId = Objects.requireNonNull(id);
        if (!UUID.fromString(couponId).toString().equalsIgnoreCase(couponId)) {
            throw new IllegalStateException("coupon id must be a canonical UUID");
        }
        if (fixedDiscount != null && fixedDiscount < 0) {
            throw new IllegalStateException("fixedDiscount is negative");
        }
        return new ProductCoupon(couponId, Objects.requireNonNull(name),
            Objects.requireNonNull(fixedDiscount), Objects.requireNonNull(soldOut),
            Objects.requireNonNull(endsAt), Objects.requireNonNull(expiresAt));
    }
}
