package com.shoppinglive.commerce.coupons.api;

import com.shoppinglive.commerce.coupons.application.CouponCreationService;
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

@RestController
public class SellerCouponController {
    public record CreateCoupon(@NotBlank @Size(max=100) String name, @NotNull @Positive Long fixedDiscount,
        @NotNull Integer issuanceLimit, @NotNull Instant startsAt, @NotNull Instant endsAt,
        @NotNull Instant expiresAt, @NotEmpty List<@NotNull @Positive Long> productIds) { }

    private final CouponCreationService coupons;
    public SellerCouponController(CouponCreationService coupons) { this.coupons = coupons; }

    @PostMapping("/v1/seller/coupons")
    public ResponseEntity<ApiResponse<CouponDefinition>> create(@AuthenticationPrincipal AuthenticatedUser seller,
            @Valid @RequestBody CreateCoupon request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(coupons.create(seller.memberId(), request.name(),
            request.fixedDiscount(), request.issuanceLimit(), request.startsAt(), request.endsAt(),
            request.expiresAt(), request.productIds())));
    }
}
