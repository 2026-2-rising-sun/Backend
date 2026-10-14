package com.shoppinglive.commerce.sales.exception;

import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 판매 상태 전이 규칙을 위반한 요청에 던지는 예외.
 *
 * <p>예: {@code ON_SALE} 에서 {@code READY} 로 되돌리려 하거나, 관리자가 {@code SOLD_OUT} 을 직접 지정하려는 경우. Spring 이
 * 400 Bad Request 로 매핑한다.
 */
public class IllegalStateTransitionException extends BusinessException {

    public IllegalStateTransitionException(SalesStatus current, SalesStatus target) {
        super(ErrorCode.INVALID_REQUEST, "허용되지 않는 상태 변경입니다: " + current + " -> " + target);
    }

    public IllegalStateTransitionException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
