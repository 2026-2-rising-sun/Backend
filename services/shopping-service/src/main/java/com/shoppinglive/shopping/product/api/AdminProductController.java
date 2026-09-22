package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import com.shoppinglive.shopping.product.application.ProductRegistrationService;
import com.shoppinglive.shopping.product.application.ProductUpdateService;
import com.shoppinglive.shopping.product.domain.Product;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 상품 API. 기본정보만 등록된 상품은 Commerce 판매정보(판매 1)가 생기기 전까지 공개·주문 대상이 아니다.
 * 아직 인증이 없다 (member 연동 시 관리자 권한 검사 추가).
 */
@RestController
@RequestMapping("/v1/admin/products")
public class AdminProductController {

    static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 64;

    private final ProductRegistrationService registrationService;
    private final ProductUpdateService updateService;
    private final ImageUrlResolver imageUrlResolver;

    public AdminProductController(ProductRegistrationService registrationService, ProductUpdateService updateService,
            ImageUrlResolver imageUrlResolver) {
        this.registrationService = registrationService;
        this.updateService = updateService;
        this.imageUrlResolver = imageUrlResolver;
    }

    /**
     * 상품 기본정보 등록. 같은 {@code X-Idempotency-Key} 로 다시 보내면 새 상품을 만들지 않고 처음 등록된 상품을
     * 같은 201 응답으로 돌려준다 (재전송한 클라이언트가 첫 응답과 구분할 필요가 없도록). 재요청 본문은 보지 않는다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> register(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RegisterProductRequest request) {
        // 헤더 누락을 스프링에 맡기면 공통 핸들러가 500 으로 돌려서 직접 검사한다.
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    IDEMPOTENCY_KEY_HEADER + " 헤더가 필요합니다 (1~" + IDEMPOTENCY_KEY_MAX_LENGTH + "자).");
        }
        Product product = registrationService.register(request.toCommand(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toResponse(product)));
    }

    /** 기본정보 부분 수정·대표 이미지 교체. 응답의 version 을 다음 수정 요청에 그대로 보내야 한다. */
    @PatchMapping("/{productId}")
    public ApiResponse<ProductResponse> update(@PathVariable Long productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ApiResponse.ok(toResponse(updateService.update(productId, request.toCommand())));
    }

    private ProductResponse toResponse(Product product) {
        return ProductResponse.of(product, imageUrlResolver.urlOf(product.getMainImageId()));
    }
}
