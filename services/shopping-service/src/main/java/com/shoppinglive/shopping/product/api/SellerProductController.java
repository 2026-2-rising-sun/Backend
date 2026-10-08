package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.shopping.product.application.ProductQueryService;
import com.shoppinglive.shopping.product.application.SellerProductPage;
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

    @GetMapping("/v1/seller/products")
    public ApiResponse<SellerProductPage> list(@AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(products.findOwnedProducts(seller.memberId(), page, size));
    }
}
