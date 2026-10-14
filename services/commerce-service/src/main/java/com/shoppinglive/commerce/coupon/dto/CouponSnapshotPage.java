package com.shoppinglive.commerce.coupon.dto;

import java.util.List;

public record CouponSnapshotPage(
        List<CouponDisplaySnapshot> items, String nextCursor, boolean hasNext) {
    public CouponSnapshotPage {
        items = List.copyOf(items);
    }
}
