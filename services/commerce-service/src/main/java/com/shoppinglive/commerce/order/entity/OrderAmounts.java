package com.shoppinglive.commerce.order.entity;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/** 주문서 조회, 직접 주문, 장바구니 주문에 동일한 범위 검사를 포함한 정수 계산을 적용한다. */
public final class OrderAmounts {
    private OrderAmounts() {}

    public static long total(long unitPrice, int quantity) {
        if (unitPrice <= 0 || quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "가격과 수량은 양수여야 합니다.");
        }
        try {
            return Math.multiplyExact(unitPrice, (long) quantity);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "주문 금액이 허용 범위를 초과합니다.");
        }
    }
}
