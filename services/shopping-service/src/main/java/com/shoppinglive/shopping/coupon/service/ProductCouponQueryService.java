package com.shoppinglive.shopping.coupon.service;

import com.shoppinglive.shopping.coupon.dto.ProductCouponResponse;
import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** 상품 화면의 쿠폰 목록. replica 만 읽고 Commerce 는 부르지 않는다. */
@Service
public class ProductCouponQueryService {

    private final CouponDisplayRepository replica;
    private final Clock clock = Clock.systemUTC();

    public ProductCouponQueryService(CouponDisplayRepository replica) {
        this.replica = replica;
    }

    /** 지금 발급 기간인 쿠폰. 쿠폰이 없거나 모르는 상품이면 빈 목록이다. */
    public List<ProductCouponResponse> findActive(long productId) {
        return replica.findActiveByProductId(productId, clock.instant());
    }
}
