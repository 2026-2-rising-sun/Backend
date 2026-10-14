package com.shoppinglive.commerce.coupon.dto;

import java.time.Instant;
import java.util.List;

/** Shopping 표시용 전체 상태. 발급·사용 가능 여부의 최종 판정은 Commerce가 맡는다. */
public record CouponDisplaySnapshot(
        String couponId,
        long snapshotVersion,
        String name,
        long fixedDiscount,
        List<Long> productIds,
        Instant startsAt,
        Instant endsAt,
        Instant expiresAt,
        boolean soldOut) {
    public CouponDisplaySnapshot {
        productIds = List.copyOf(productIds);
    }
}
