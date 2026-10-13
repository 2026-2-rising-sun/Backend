package com.shoppinglive.shopping.coupon.application;

import java.util.List;

/**
 * 상품 id 로 적용 가능한 쿠폰을 조회하는 포트. 회원과 무관하게 같은 결과를 준다.
 */
public interface ProductCouponClient {

    /**
     * @throws ProductCouponUnavailableException 쿠폰을 확인하지 못함 (네트워크·5xx·timeout·계약 위반)
     */
    List<ProductCoupon> findByProductId(long productId);
}
