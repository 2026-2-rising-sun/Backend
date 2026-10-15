package com.shoppinglive.shopping.coupon.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.coupon.dto.ProductCouponResponse;
import com.shoppinglive.shopping.coupon.service.ProductCouponQueryService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 사용자용 상품 쿠폰 API. 인증 없이 조회할 수 있다. */
@RestController
public class ProductCouponController {

    private final ProductCouponQueryService couponService;

    public ProductCouponController(ProductCouponQueryService couponService) {
        this.couponService = couponService;
    }

    @Operation(summary = "상품에 적용 가능한 쿠폰")
    @GetMapping("/v1/products/{productId}/coupons")
    public ApiResponse<List<ProductCouponResponse>> list(
            // contracts/api/shopping-service.yaml 의 ProductId(minimum: 1)와 맞춘다. 0·음수는 빈 목록이 아니라 400
            // 이다.
            @PathVariable @Positive long productId) {
        return ApiResponse.ok(couponService.findActive(productId));
    }
}
