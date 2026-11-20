package com.shoppinglive.commercecore.payment.dto;

public record RefundableTarget(
        long orderId,
        Long cartItemId,
        String productName,
        int quantity,
        long refundAmount,
        boolean refundable,
        String unavailableReason,
        long unitPrice,
        int succeededQuantity,
        int heldQuantity,
        int refundableQuantity,
        long remainingRefundableAmount) {
    public RefundableTarget(
            long orderId,
            Long cartItemId,
            String productName,
            int quantity,
            long refundAmount,
            boolean refundable,
            String unavailableReason) {
        this(
                orderId,
                cartItemId,
                productName,
                quantity,
                refundAmount,
                refundable,
                unavailableReason,
                0,
                0,
                0,
                refundable ? quantity : 0,
                refundAmount);
    }
}
