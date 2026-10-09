package com.shoppinglive.commerce.coupons.api;

import com.shoppinglive.commerce.coupons.application.CouponCreationService;
import com.shoppinglive.commerce.coupons.application.CouponManagementService;
import com.shoppinglive.commerce.coupons.domain.CouponDefinition;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
public class SellerCouponController {
    public record CreateCoupon(@NotBlank @Size(max=100) String name, @NotNull @Positive Long fixedDiscount,
        @NotNull Integer issuanceLimit, @NotNull Instant startsAt, @NotNull Instant endsAt,
        @NotNull Instant expiresAt, @NotEmpty List<@NotNull @Positive Long> productIds) { }

    private final CouponCreationService coupons;
    private final CouponManagementService management;
    public SellerCouponController(CouponCreationService coupons,CouponManagementService management) {
        this.coupons=coupons; this.management=management;
    }

    public record EditCoupon(@NotNull @jakarta.validation.constraints.PositiveOrZero Long version,
        @NotNull @Valid CreateCoupon settings) { }

    @GetMapping("/v1/seller/coupons")
    public ApiResponse<CouponManagementService.Page> list(@AuthenticationPrincipal AuthenticatedUser seller,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(management.list(seller.memberId(),page,size));
    }

    @GetMapping("/v1/seller/coupons/{id}")
    public ApiResponse<CouponDefinition> get(@AuthenticationPrincipal AuthenticatedUser seller,@PathVariable String id) {
        return ApiResponse.ok(management.get(seller.memberId(),id));
    }

    @PatchMapping("/v1/seller/coupons/{id}")
    public ApiResponse<CouponDefinition> update(@AuthenticationPrincipal AuthenticatedUser seller,
            @PathVariable String id,@Valid @RequestBody EditCoupon request) {
        CreateCoupon s=request.settings();
        return ApiResponse.ok(management.update(seller.memberId(),id,request.version(),s.name(),s.fixedDiscount(),
            s.issuanceLimit(),s.startsAt(),s.endsAt(),s.expiresAt(),s.productIds()));
    }

    @PostMapping("/v1/seller/coupons")
    public ResponseEntity<ApiResponse<CouponDefinition>> create(@AuthenticationPrincipal AuthenticatedUser seller,
            @Valid @RequestBody CreateCoupon request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(coupons.create(seller.memberId(), request.name(),
            request.fixedDiscount(), request.issuanceLimit(), request.startsAt(), request.endsAt(),
            request.expiresAt(), request.productIds())));
    }
}
