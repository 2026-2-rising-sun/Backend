package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 재고가 모자라 요청을 처리할 수 없을 때 던지는 예외 (409).
 *
 * <p>두 자리에서 쓴다.
 * <ul>
 *   <li>관리자 재고 감소 (판매 3) — 감소 후 {@code available} 이 음수가 되는 경우</li>
 *   <li>주문 생성 (주문 2) — 주문 수량만큼 배정할 재고가 없는 경우. 사용자에게는 품절·재고
 *       부족으로 안내한다</li>
 * </ul>
 *
 * <p>{@link BusinessException} 상속으로 바꾼 이유는 {@link SalesNotFoundException} 주석 참고.
 * {@code @ResponseStatus} 만으로는 공통 예외 핸들러에 가려 500 으로 나갔다.
 */
public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
