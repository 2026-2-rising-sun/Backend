package com.shoppinglive.commercecore.order.dto;

import java.time.Instant;
import java.util.List;

public record OrderSheetRefundResponse(
        long refundRequestId,
        String orderNumber,
        String status,
        long refundAmount,
        Instant requestedAt,
        Instant resolvedAt,
        List<Item> items) {
    public record Item(long orderItemId, long skuId, int quantity, long refundAmount) {}
}
