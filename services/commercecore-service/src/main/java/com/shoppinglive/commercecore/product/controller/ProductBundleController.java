package com.shoppinglive.commercecore.product.controller;

import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.product.service.ProductBundleRegistrationService;
import com.shoppinglive.common.core.*;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductBundleController {
    private final ProductBundleRegistrationService registration;

    public ProductBundleController(ProductBundleRegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping(
            path = "/v1/seller/products/registrations",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProductBundleResponse>> register(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String key,
            @Valid @RequestPart("request") RegisterProductBundleRequest request,
            @RequestPart("file") MultipartFile file)
            throws IOException {
        if (key == null || key.isBlank() || key.length() > 64)
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "X-Idempotency-Key(1~64자)가 필요합니다.");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ApiResponse.ok(
                                registration.register(
                                        seller.memberId(),
                                        key,
                                        request,
                                        file.getBytes(),
                                        file.getContentType(),
                                        file.getOriginalFilename())));
    }
}
