package com.shoppinglive.commerce.payment.exception;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 결제 대상이 아닌 주문에 결제 시작 요청이 들어온 경우 (예: 이미 결제 진행 중·완료·취소·만료).
 *
 * <p>Spring 이 409 Conflict 로 매핑한다.
 */
public class OrderNotEligibleForPaymentException extends BusinessException {

    public OrderNotEligibleForPaymentException(String orderNumber) {
        super(ErrorCode.CONFLICT, "결제 대기 상태의 주문이 아닙니다: orderNumber=" + orderNumber);
    }
}
