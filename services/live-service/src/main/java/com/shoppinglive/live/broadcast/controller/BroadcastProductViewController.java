package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.dto.BroadcastProductViewResponse;
import com.shoppinglive.live.broadcast.service.BroadcastProductViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BroadcastProductViewController {
    private final BroadcastProductViewService service;

    public BroadcastProductViewController(final BroadcastProductViewService service) {
        this.service = service;
    }

    @Operation(
            summary = "관리 방송 상품 조회 (#67)",
            description =
                    "연결 순서로 Shopping 기본정보와 Commerce 최신 판매정보를 결합한다.\n정상 조회에서 사라진 연결도 linkId/position 과 `missing: true` 로 식별할 수 있다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/admin/broadcasts/{id}/products")
    public ApiResponse<List<BroadcastProductViewResponse>> admin(@PathVariable final long id) {
        return ApiResponse.ok(service.forAdmin(id));
    }

    @Operation(
            summary = "공개 방송 상품 조회·구매 진입 (#67)",
            description =
                    "ON_SALE/SOLD_OUT 만 노출하고 READY/PRIVATE·정상 조회에서 삭제된 상품은 제외한다.\n품절은 구매 불가다. FE 는 productId/salesId 를 그대로 일반 주문 흐름에 넘기며\nLive 전용 주문·결제 API 는 없다. 최종 가격·재고 판정은 Commerce 가 한다.\n필수 외부 조회가 실패하면 빈 목록으로 숨기지 않고 503 을 반환한다.")
    @GetMapping("/v1/broadcasts/{id}/products")
    public ApiResponse<List<BroadcastProductViewResponse>> publicView(@PathVariable final long id) {
        return ApiResponse.ok(service.forPublic(id));
    }
}
