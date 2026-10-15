package com.shoppinglive.shopping.coupon.dto;

import java.time.Instant;

/**
 * 상품 화면에 보여 줄 쿠폰 한 건. 표시용이다. 받을 수 있는지와 최종 할인 금액은 Commerce 가 받기·주문 때 다시 판정한다.
 *
 * @param fixedDiscount 정액 할인 (원). 쿠폰 적용 예상가는 화면에서 {@code 판매가 - fixedDiscount} 로 계산한다
 * @param soldOut 발급 수량이 모두 소진됐는지. 소진돼도 기간 안이면 목록에 남겨 "소진"으로 보여 준다
 */
public record ProductCouponResponse(
        String couponId,
        String name,
        long fixedDiscount,
        Instant startsAt,
        Instant endsAt,
        Instant expiresAt,
        boolean soldOut) {}
