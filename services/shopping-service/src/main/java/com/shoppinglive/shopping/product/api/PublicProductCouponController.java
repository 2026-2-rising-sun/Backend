package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.coupon.application.ProductCouponService;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 상품 쿠폰 목록 (표시용). 인증 없이 조회한다.
 *
 * <p>상품을 먼저 조회하지 않는다. 없는 상품 id 는 Commerce 가 쿠폰을 주지 않으므로 빈 목록이 되고, 상세 조회와 독립이다.
 * Commerce 를 확인하지 못해도 200 과 빈 목록으로 응답한다.
 */
// @Validated 를 붙이지 않는다: 붙이면 AOP 경로가 ConstraintViolationException 을 던져 500 이 된다.
@RestController
@RequestMapping("/v1/products/{productId}")
@RequiredArgsConstructor
public class PublicProductCouponController {

    private final ProductCouponService couponService;

    @GetMapping("/coupons")
    public ApiResponse<List<PublicProductCouponItem>> listCoupons(@PathVariable @Positive Long productId) {
        return ApiResponse.ok(couponService.findByProductId(productId).stream()
            .map(PublicProductCouponItem::from)
            .toList());
    }
}
