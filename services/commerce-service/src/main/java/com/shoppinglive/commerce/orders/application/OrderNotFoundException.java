package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 주문 조회 실패 예외.
 *
 * <p>실제 원인은 두 가지가 있지만 (존재하지 않는 주문번호 · 다른 회원 소유) 응답은
 * 동일하게 404 Not Found 로 통합한다. 존재 여부 · 소유권 정보의 leak 을 방지하기 위함.
 */
public class OrderNotFoundException extends BusinessException {

    public OrderNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
