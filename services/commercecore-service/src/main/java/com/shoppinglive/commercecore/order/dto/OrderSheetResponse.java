package com.shoppinglive.commercecore.order.dto;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import java.time.Instant;
import java.util.List;

/** 외부 주문번호는 묶음 하나, 구매 항목은 저장된 SKU 스냅샷으로 표현한다. */
public record OrderSheetResponse(
        String orderNumber,
        String status,
        String failureReason,
        long totalAmount,
        long discountAmount,
        long payableAmount,
        Instant expiresAt,
        Long paymentId,
        String couponId,
        Long couponOrderItemId,
        List<Item> items) {
    public static OrderSheetResponse from(PaymentGroup group, List<Order> items) {
        String status =
                switch (group.getStatus()) {
                    case PENDING_PAYMENT, PAYMENT_CONFIRMING -> "PENDING";
                    default -> group.getStatus().name();
                };
        return new OrderSheetResponse(
                group.getGroupNumber(),
                status,
                group.getFailureReason(),
                group.getTotalAmount(),
                group.getDiscountAmount(),
                group.getPayableAmount(),
                group.getExpiresAt(),
                group.getPaymentId(),
                group.getCouponId(),
                group.getCouponOrderItemId(),
                items.stream().map(Item::from).toList());
    }

    public record Item(
            Long orderItemId,
            Long skuId,
            Long productId,
            String productName,
            String sellerId,
            String mainImageUrl,
            String selectedOptions,
            int quantity,
            long unitPrice,
            long totalAmount,
            long discountAmount,
            long payableAmount,
            Long sourceCartItemId,
            Long sourceCartVersion) {
        private static Item from(Order o) {
            return new Item(
                    o.getId(),
                    o.getSkuId(),
                    o.getProductIdSnapshot(),
                    o.getProductNameSnapshot(),
                    o.getSellerIdSnapshot(),
                    o.getMainImageSnapshot(),
                    o.getSelectedOptionsSnapshot(),
                    o.getQuantity(),
                    o.getUnitPrice(),
                    o.getTotalAmount(),
                    o.getDiscountAmount(),
                    o.getPayableAmount(),
                    o.getSourceCartItemId(),
                    o.getSourceCartItemVersion());
        }
    }
}
