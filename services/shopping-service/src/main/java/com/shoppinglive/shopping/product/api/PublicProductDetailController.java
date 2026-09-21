package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.product.application.PublicProductDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 사용자용 공개 상품 상세 API. 인증 없이 조회할 수 있다. */
@RestController
@RequestMapping("/v1/products/{productId}")
public class PublicProductDetailController {

    private final PublicProductDetailService detailService;

    public PublicProductDetailController(PublicProductDetailService detailService) {
        this.detailService = detailService;
    }

    /** 없거나 비공개면 404 NOT_FOUND, 판매정보 조회 실패면 503 SALES_INFO_UNAVAILABLE. */
    @GetMapping
    public ApiResponse<PublicProductDetailResponse> get(@PathVariable Long productId) {
        return ApiResponse.ok(PublicProductDetailResponse.from(detailService.getDetail(productId)));
    }
}
