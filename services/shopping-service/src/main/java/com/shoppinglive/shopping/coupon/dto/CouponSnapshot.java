package com.shoppinglive.shopping.coupon.dto;

import java.time.Instant;
import java.util.List;

/**
 * Commerce 쿠폰 표시정보의 전체 상태 한 건. 쿠폰 이벤트 payload 와 동기화 API 응답이 같은 모양이라 둘 다 이 값으로 바꿔 저장한다.
 *
 * @param version Commerce 의 {@code snapshotVersion}. 쿠폰별로 증가한다
 * @param productIds 쿠폰을 쓸 수 있는 상품 전체. 이전 목록을 대체하며, 빈 목록이면 모든 연결을 지운다
 * @param startsAt 발급 시작 (포함)
 * @param endsAt 발급 종료 (제외)
 * @param expiresAt 발급받은 쿠폰의 사용 만료
 */
public record CouponSnapshot(
        String couponId,
        long version,
        String name,
        long fixedDiscount,
        List<Long> productIds,
        Instant startsAt,
        Instant endsAt,
        Instant expiresAt,
        boolean soldOut) {

    public CouponSnapshot {
        productIds = List.copyOf(productIds);
    }
}
