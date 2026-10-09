package com.shoppinglive.commerce.coupons.api;

import com.shoppinglive.commerce.coupons.application.CouponClaimService;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class MemberCouponController {
    private final CouponClaimService coupons;
    public MemberCouponController(CouponClaimService coupons) { this.coupons=coupons; }
    @GetMapping("/v1/coupons")
    public ApiResponse<List<CouponClaimService.AvailableCoupon>> available(@AuthenticationPrincipal AuthenticatedUser member,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(coupons.available(member.memberId(),page,size));
    }
    @PostMapping("/v1/coupons/{id}/claims")
    public ResponseEntity<ApiResponse<CouponClaimService.MemberCoupon>> claim(@AuthenticationPrincipal AuthenticatedUser member,
            @PathVariable String id) {
        var claim=coupons.claim(member.memberId(),id);
        return ResponseEntity.status(claim.created()?201:200).body(ApiResponse.ok(claim.coupon()));
    }
    @GetMapping("/v1/me/coupons")
    public ApiResponse<List<CouponClaimService.MemberCoupon>> owned(@AuthenticationPrincipal AuthenticatedUser member,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(coupons.list(member.memberId(),page,size));
    }
}
