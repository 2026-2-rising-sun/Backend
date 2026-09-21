package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.product.application.PublicProductDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자용 공개 상품 상세 API. 인증 없이 조회할 수 있다. */
@RestController
@RequestMapping("/v1/products/{productId}")
public class PublicProductDetailController {

    /** 1 이상의 정수. 부호·선행 0·소수점을 막고, 9자리로 제한해 int 범위를 넘지 않게 한다. */
    private static final String POSITIVE_INT = "[1-9][0-9]{0,8}";

    private final PublicProductDetailService detailService;

    public PublicProductDetailController(PublicProductDetailService detailService) {
        this.detailService = detailService;
    }

    /** 없거나 비공개면 404 NOT_FOUND, 판매정보 조회 실패면 503 SALES_INFO_UNAVAILABLE. */
    @GetMapping
    public ApiResponse<PublicProductDetailResponse> get(@PathVariable Long productId) {
        return ApiResponse.ok(PublicProductDetailResponse.from(detailService.getDetail(productId)));
    }

    /**
     * 주문 화면 진입 전 수량 사전 확인. 재고를 예약하지 않으며 최종 검증은 주문 생성 시 Commerce 가 한다.
     * 수량을 문자열로 받아 직접 해석한다: 0·음수·소수·문자·빈 값은 스프링 변환에 맡기면 500 이 되거나
     * 조용히 통과할 수 있어서다. 수량 오류는 400 이 공개 여부 확인(404/503)보다 먼저다.
     */
    @GetMapping("/purchase-check")
    public ApiResponse<PurchaseCheckResponse> checkPurchase(@PathVariable Long productId,
            @RequestParam(name = "quantity", required = false) String quantity) {
        return ApiResponse.ok(PurchaseCheckResponse.from(detailService.checkPurchase(productId, parseQuantity(quantity))));
    }

    private static int parseQuantity(String quantity) {
        if (quantity == null || !quantity.matches(POSITIVE_INT)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "quantity 는 1~999999999 사이의 정수여야 합니다.");
        }
        return Integer.parseInt(quantity);
    }
}
