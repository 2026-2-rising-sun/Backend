package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.live.broadcast.dto.BroadcastProductLinkResponse;
import com.shoppinglive.live.broadcast.dto.LinkProductInput;
import com.shoppinglive.live.broadcast.dto.ReorderProductsInput;
import com.shoppinglive.live.broadcast.entity.BroadcastProduct;
import com.shoppinglive.live.broadcast.service.BroadcastProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/broadcasts/{id}/products")
public class BroadcastProductController {
    private final BroadcastProductService service;

    public BroadcastProductController(final BroadcastProductService service) {
        this.service = service;
    }

    @Operation(
            summary = "방송 상품 연결 (#62)",
            description =
                    "Shopping 의 상품 존재와 Commerce 판매 상태를 확인한 뒤 ON_SALE/SOLD_OUT 만 연결한다.\nPREPARING/LIVE 에서만 허용하고 방송당 최대 100개, 같은 productId 중복은 409 다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BroadcastProductLinkResponse> link(
            @PathVariable final long id, @Valid @RequestBody final LinkProductInput input) {
        final BroadcastProduct link = service.link(id, input.productId(), input.expectedVersion());
        return ApiResponse.ok(BroadcastProductLinkResponse.from(link));
    }

    @Operation(
            summary = "방송 상품 노출 순서 변경 (#63)",
            description =
                    "이 방송의 전체 linkId 순열을 받아 한 번에 순서를 바꾼다. 중복·누락·타 방송 ID 는 400 이고\n성공·실패 모두 원자적이라 일부 순서만 남지 않는다. ENDED 는 변경할 수 없다.")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/order")
    public ApiResponse<List<BroadcastProductLinkResponse>> reorder(
            @PathVariable final long id, @Valid @RequestBody final ReorderProductsInput input) {
        return ApiResponse.ok(
                service.reorder(id, input.linkIds(), input.expectedVersion()).stream()
                        .map(BroadcastProductLinkResponse::from)
                        .toList());
    }

    /** 성공은 204 무본문이다. */
    @Operation(
            summary = "방송 상품 연결 해제 (#62)",
            description =
                    "방송 존재·상태를 먼저 검사한다. 같은 방송에서 이미 사라진 linkId 는 204 이고,\n다른 방송 소속으로 확인되면 404 다. LIVE 의 마지막 연결 상품 해제는 409 다.")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping("/{linkId}")
    public ResponseEntity<Void> unlink(
            @PathVariable final long id,
            @PathVariable final long linkId,
            @RequestParam @PositiveOrZero(message = "0 이상이어야 합니다.") final long expectedVersion) {
        service.unlink(id, linkId, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
