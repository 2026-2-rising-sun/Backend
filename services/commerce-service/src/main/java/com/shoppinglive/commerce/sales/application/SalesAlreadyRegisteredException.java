package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 이미 판매정보가 등록된 상품에 다시 최초 설정을 요청했을 때 던지는 예외 (판매 1).
 *
 * <p>판매 1 완료 기준의 "이미 판매정보가 있다면 재등록으로 초기화하지 않고 수정 기능으로
 * 안내한다" 를 표현한다. 재등록이 성공하면 현재 재고가 초기 재고로 되돌아가 이미 배정된 주문
 * 수량이 사라지므로, 조용히 덮어쓰지 않고 409 로 거절한다.
 *
 * <p>{@link BusinessException} 을 상속해 {@code common-web} 의 {@code GlobalExceptionHandler}
 * 가 표준 오류 응답과 409 상태코드로 매핑하게 한다.
 */
public class SalesAlreadyRegisteredException extends BusinessException {

    public SalesAlreadyRegisteredException(Long productId) {
        super(ErrorCode.CONFLICT, "이미 판매정보가 등록된 상품입니다: productId=" + productId);
    }

    public SalesAlreadyRegisteredException(Long productId, Throwable cause) {
        super(ErrorCode.CONFLICT, "이미 판매정보가 등록된 상품입니다: productId=" + productId, cause);
    }
}
