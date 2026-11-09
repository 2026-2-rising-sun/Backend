package com.shoppinglive.commercecore.payment.dto;

public record RefundableTarget(
        long orderId,
        Long cartItemId,
        String productName,
        int quantity,
        long refundAmount,
        boolean refundable,
        String unavailableReason) {}
