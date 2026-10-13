package com.shoppinglive.shopping.coupon.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code shopping.coupons.*} 설정. Pod 로컬 캐시의 유지 시간이다.
 *
 * @param cacheTtl 쿠폰 목록(성공·실패 모두)을 갱신 없이 쓰는 시간. 지나면 다음 조회 때 백그라운드로 갱신한다.
 *     화면에 몇 초 늦은 쿠폰 목록은 허용한다.
 */
@ConfigurationProperties("shopping.coupons")
public record ProductCouponProperties(Duration cacheTtl) {

    public ProductCouponProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(3) : cacheTtl;
    }
}
