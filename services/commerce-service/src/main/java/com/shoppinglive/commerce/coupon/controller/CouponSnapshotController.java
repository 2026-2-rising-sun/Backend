package com.shoppinglive.commerce.coupon.controller;

import com.shoppinglive.commerce.coupon.dto.CouponDisplaySnapshot;
import com.shoppinglive.commerce.coupon.dto.CouponSnapshotPage;
import com.shoppinglive.commerce.coupon.service.CouponSnapshotService;
import com.shoppinglive.common.core.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CouponSnapshotController {
    private final CouponSnapshotService snapshots;

    public CouponSnapshotController(CouponSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @GetMapping("/v1/internal/coupon-snapshots")
    public ApiResponse<CouponSnapshotPage> list(
            @RequestParam(required = false) String afterId,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(snapshots.list(afterId, size));
    }

    @GetMapping("/v1/internal/coupon-snapshots/{couponId}")
    public ApiResponse<CouponDisplaySnapshot> get(@PathVariable String couponId) {
        return ApiResponse.ok(snapshots.get(couponId));
    }
}
