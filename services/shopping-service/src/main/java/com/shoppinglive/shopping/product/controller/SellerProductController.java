package com.shoppinglive.shopping.product.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.shopping.product.dto.SellerProductPage;
import com.shoppinglive.shopping.product.service.ProductQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SellerProductController {
    private final ProductQueryService products;

    public SellerProductController(ProductQueryService products) {
        this.products = products;
    }

    @Operation(
            summary = "본인 소유 상품 목록",
            description =
                    "인증된 판매자의 상품만 최신 등록순으로 조회한다. 기존 소유자 미지정 상품과 다른 판매자의 상품은 제외한다. 판매정보 조회와 독립이다. page와 size는 정수이며 page × size가 2147483647을 초과하면 400으로 거절한다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/seller/products")
    public ApiResponse<SellerProductPage> list(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(products.findOwnedProducts(seller.memberId(), page, size));
    }
}
