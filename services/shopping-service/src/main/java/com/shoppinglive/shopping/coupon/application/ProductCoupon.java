package com.shoppinglive.shopping.coupon.application;

import java.time.Instant;

/**
 * 상품에 적용 가능한 쿠폰 한 건. 표시용이며 할인 금액 계산은 Commerce 주문서 조회가 담당한다.
 *
 * @param fixedDiscount 정액 할인 (원)
 * @param soldOut 발급 수량이 모두 소진되었는지
 */
public record ProductCoupon(String id, String name, long fixedDiscount, boolean soldOut, Instant endsAt,
        Instant expiresAt) {
}
