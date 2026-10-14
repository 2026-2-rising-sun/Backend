package com.shoppinglive.commerce.coupon.controller;

import com.shoppinglive.commerce.coupon.service.CouponClaimService;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class MemberCouponController {
    private final CouponClaimService coupons;

    public MemberCouponController(CouponClaimService coupons) {
        this.coupons = coupons;
    }

    @Operation(
            summary = "회원이 수령할 수 있는 쿠폰 조회",
            description =
                    "현재 이벤트 중이고 수량이 남은 쿠폰. 같은 종류를 이미 수령한 회원에게는 노출하지 않는다. 이벤트 종료와 쿠폰 사용 만료는 별도다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/coupons")
    public ApiResponse<List<CouponClaimService.AvailableCoupon>> available(
            @AuthenticationPrincipal AuthenticatedUser member,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(coupons.available(member.memberId(), page, size));
    }

    @Operation(
            summary = "쿠폰 수령",
            description =
                    "같은 회원·쿠폰의 재요청은 기존 수령 결과를 반환하며 추가 발급하지 않는다. 회원은 같은 종류를 한 번만 수령할 수 있고 환불 후에도 재수령할 수 없다. 이벤트 시작은 포함, 종료는 제외한다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/v1/coupons/{id}/claims")
    public ResponseEntity<ApiResponse<CouponClaimService.MemberCoupon>> claim(
            @AuthenticationPrincipal AuthenticatedUser member, @PathVariable String id) {
        var claim = coupons.claim(member.memberId(), id);
        return ResponseEntity.status(claim.created() ? 201 : 200)
                .body(ApiResponse.ok(claim.coupon()));
    }

    @Operation(summary = "내 쿠폰과 상태 조회")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/me/coupons")
    public ApiResponse<List<CouponClaimService.MemberCoupon>> owned(
            @AuthenticationPrincipal AuthenticatedUser member,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(coupons.list(member.memberId(), page, size));
    }
}
