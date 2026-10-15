package com.shoppinglive.commerce.coupon.controller;

import com.shoppinglive.commerce.coupon.entity.CouponDefinition;
import com.shoppinglive.commerce.coupon.service.CouponCreationService;
import com.shoppinglive.commerce.coupon.service.CouponManagementService;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SellerCouponController {
    public record CreateCoupon(
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Size(max = 100, message = "크기는 {min}~{max} 범위여야 합니다.")
                    String name,
            @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Long fixedDiscount,
            @NotNull(message = "필수 값입니다.") Integer issuanceLimit,
            @NotNull(message = "필수 값입니다.") Instant startsAt,
            @NotNull(message = "필수 값입니다.") Instant endsAt,
            @NotNull(message = "필수 값입니다.") Instant expiresAt,
            @NotEmpty(message = "비어 있을 수 없습니다.")
                    List<@NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Long>
                            productIds) {}

    private final CouponCreationService coupons;
    private final CouponManagementService management;

    public SellerCouponController(
            CouponCreationService coupons, CouponManagementService management) {
        this.coupons = coupons;
        this.management = management;
    }

    public record EditCoupon(
            @NotNull(message = "필수 값입니다.") @jakarta.validation.constraints.PositiveOrZero
                    Long version,
            @NotNull(message = "필수 값입니다.") @Valid CreateCoupon settings) {}

    @GetMapping("/v1/seller/coupons")
    public ApiResponse<CouponManagementService.Page> list(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(management.list(seller.memberId(), page, size));
    }

    @GetMapping("/v1/seller/coupons/{id}")
    public ApiResponse<CouponDefinition> get(
            @AuthenticationPrincipal AuthenticatedUser seller, @PathVariable String id) {
        return ApiResponse.ok(management.get(seller.memberId(), id));
    }

    @PatchMapping("/v1/seller/coupons/{id}")
    public ApiResponse<CouponDefinition> update(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @PathVariable String id,
            @Valid @RequestBody EditCoupon request) {
        CreateCoupon s = request.settings();
        return ApiResponse.ok(
                management.update(
                        seller.memberId(),
                        id,
                        request.version(),
                        s.name(),
                        s.fixedDiscount(),
                        s.issuanceLimit(),
                        s.startsAt(),
                        s.endsAt(),
                        s.expiresAt(),
                        s.productIds()));
    }

    @PostMapping("/v1/seller/coupons")
    public ResponseEntity<ApiResponse<CouponDefinition>> create(
            @AuthenticationPrincipal AuthenticatedUser seller,
            @Valid @RequestBody CreateCoupon request) {
        return ResponseEntity.status(201)
                .body(
                        ApiResponse.ok(
                                coupons.create(
                                        seller.memberId(),
                                        request.name(),
                                        request.fixedDiscount(),
                                        request.issuanceLimit(),
                                        request.startsAt(),
                                        request.endsAt(),
                                        request.expiresAt(),
                                        request.productIds())));
    }
}
