package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.product.application.AdminProductDetail;
import com.shoppinglive.shopping.product.application.AdminProductPage;
import com.shoppinglive.shopping.product.application.AdminProductQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리용 상품 목록·상세 조회. 판매정보를 못 불러와도 200 으로 기본정보를 주고 판매 상태만 {@code UNKNOWN} 이다.
 * 아직 인증이 없다 (member 연동 시 관리자 권한 검사 추가).
 */
@RestController
@RequestMapping("/v1/admin/products")
public class AdminProductQueryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminProductQueryService queryService;

    public AdminProductQueryController(AdminProductQueryService queryService) {
        this.queryService = queryService;
    }

    /** 최신 등록순. page 는 0부터, size 는 1~100. */
    @GetMapping
    public ApiResponse<AdminProductPage> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.");
        }
        return ApiResponse.ok(queryService.list(page, size));
    }

    /** 없으면 404 NOT_FOUND. 응답의 version 을 기본정보 수정 요청에 그대로 보낸다. */
    @GetMapping("/{productId}")
    public ApiResponse<AdminProductDetail> get(@PathVariable Long productId) {
        return ApiResponse.ok(queryService.get(productId));
    }
}
