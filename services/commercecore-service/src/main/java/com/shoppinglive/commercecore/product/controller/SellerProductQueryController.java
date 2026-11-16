package com.shoppinglive.commercecore.product.controller;

import com.shoppinglive.commercecore.product.dto.SalesDisplayStatus;
import com.shoppinglive.commercecore.product.dto.SellerProductDetail;
import com.shoppinglive.commercecore.product.dto.SellerProductPage;
import com.shoppinglive.commercecore.product.service.SellerProductCatalogQueryService;
import com.shoppinglive.commercecore.product.service.SellerProductQueryService;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 판매자 본인 상품과 판매·재고 원본을 로컬 조회한다. */
@RestController
@RequestMapping("/v1/seller/products")
public class SellerProductQueryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SellerProductQueryService queryService;
    private final SellerProductCatalogQueryService filteredQuery;

    public SellerProductQueryController(
            SellerProductQueryService queryService,
            SellerProductCatalogQueryService filteredQuery) {
        this.queryService = queryService;
        this.filteredQuery = filteredQuery;
    }

    /** 최신 등록순. page 는 0부터, size 는 1~100. */
    @GetMapping
    public ApiResponse<SellerProductPage> list(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) SalesDisplayStatus status) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.");
        }
        return ApiResponse.ok(
                status == null
                        ? queryService.list(seller.memberId(), page, size)
                        : filteredQuery.sellerList(seller.memberId(), status, page, size));
    }

    /** 상세 경로의 누락 ID는 SELLER 인가 후 입력 오류로 응답한다. */
    @GetMapping("/")
    public ApiResponse<Void> missingProductId() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "productId 는 필수입니다.");
    }

    /** 없거나 본인 상품이 아니면 404 NOT_FOUND. 응답의 version 을 기본정보 수정 요청에 그대로 보낸다. */
    @GetMapping("/{productId}")
    public ApiResponse<SellerProductDetail> get(
            @AuthenticationPrincipal AuthenticatedUser seller, @PathVariable Long productId) {
        return ApiResponse.ok(queryService.get(productId, seller.memberId()));
    }
}
