package com.shoppinglive.commercecore.order.entity;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/** 할인 전 단가를 먼저 환불하되 해당 구매 항목의 남은 실결제액을 넘지 않는다. */
public final class OrderRefundAmounts {
    private OrderRefundAmounts() {}

    public static long refund(long unitPrice, int quantity, long remainingPaidAmount) {
        if (remainingPaidAmount < 0)
            throw new BusinessException(ErrorCode.CONFLICT, "남은 환불 가능 금액이 올바르지 않습니다.");
        return Math.min(OrderAmounts.total(unitPrice, quantity), remainingPaidAmount);
    }
}
