package com.shoppinglive.shopping.product.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.client.commerce.SalesDisplayStatus;
import com.shoppinglive.shopping.product.dto.PublicProductListResponse;
import com.shoppinglive.shopping.product.service.CatalogQueryService;
import com.shoppinglive.shopping.product.service.ProductSort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 상품 목록 API (상품 5). 로그인·방송 여부와 무관하게 누구나 조회한다. 방송 연동은 필터가 아니라서 Live 를 호출하지 않는다. 가격·재고는 Commerce
 * 이벤트로 채운 판매정보 사본에서 읽으므로 Commerce 가 응답하지 않아도 목록을 반환한다.
 */
@RestController
@RequestMapping("/v1/products")
public class PublicProductListController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final CatalogQueryService catalog;

    public PublicProductListController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    /** 상세 경로에서 ID를 비운 요청은 목록 조회와 구분하여 입력 오류로 응답한다. */
    @GetMapping("/")
    public ApiResponse<Void> missingProductId() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST, "productId 는 필수입니다.");
    }

    /**
     * 판매중·품절 상품을 검색·상태·정렬 조건에 맞춰 {@code size} 개까지. 조건을 생략하면 최신순({@code sort=LATEST})과 같다. 다음 페이지는
     * 응답의 {@code nextCursor} 를 {@code cursor} 로 넘긴다. 범위 밖 size·손상된 cursor 는 400 INVALID_REQUEST.
     */
    @GetMapping
    public ApiResponse<PublicProductListResponse> list(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "status", required = false) SalesDisplayStatus status,
            @RequestParam(name = "sort", required = false) ProductSort sort) {
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "size 는 1~" + MAX_SIZE + " 이어야 합니다.");
        }
        return ApiResponse.ok(catalog.list(query, status, sort, cursor, pageSize));
    }
}
