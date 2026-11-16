package com.shoppinglive.commercecore.payment.dto;

public record RefundTarget(
        Long cartItemId,
        long orderId,
        long productId,
        String productName,
        int quantity,
        long refundAmount) {}
