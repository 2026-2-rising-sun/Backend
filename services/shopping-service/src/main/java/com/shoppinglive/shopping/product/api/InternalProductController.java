package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.product.application.ProductQueryService;
import com.shoppinglive.shopping.product.application.ProductSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 상품 조회 API (docs/internal-product-api.md). 클러스터 내부 호출용이라 Ingress 로 외부에 노출하지 않는다.
 */
@RestController
@RequestMapping("/v1/internal/products")
public class InternalProductController {

    private final ProductQueryService productQueryService;

    public InternalProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    /** 없으면 404 NOT_FOUND. */
    @GetMapping("/{productId}")
    public ApiResponse<ProductSnapshot> get(@PathVariable Long productId) {
        return ApiResponse.ok(productQueryService.getSnapshot(productId));
    }
}
