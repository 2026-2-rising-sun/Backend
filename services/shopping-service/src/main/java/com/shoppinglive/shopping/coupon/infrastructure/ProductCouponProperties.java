package com.shoppinglive.shopping.coupon.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code shopping.coupons.*} 설정. 두 값 모두 Pod 로컬 캐시의 유지 시간이다.
 *
 * @param cacheTtl 성공 결과 유지 시간. 화면에 몇 초 늦은 쿠폰 목록은 허용한다.
 * @param failureTtl 실패(빈 목록) 유지 시간. 장애 중 상품당 Commerce 호출 빈도의 상한을 정한다.
 */
@ConfigurationProperties("shopping.coupons")
public record ProductCouponProperties(Duration cacheTtl, Duration failureTtl) {

    public ProductCouponProperties {
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(3) : cacheTtl;
        failureTtl = failureTtl == null ? Duration.ofSeconds(1) : failureTtl;
    }
}
