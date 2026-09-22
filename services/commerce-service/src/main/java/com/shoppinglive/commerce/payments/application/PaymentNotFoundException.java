package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 결제 시도 조회 실패. 존재하지 않음 · 다른 주문 소유 모두 동일 404 로 통합 (leak 방지).
 */
public class PaymentNotFoundException extends BusinessException {

    public PaymentNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
