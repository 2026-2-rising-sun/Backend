package com.shoppinglive.commerce.sales.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 재고 조정이 실패했을 때 던지는 예외.
 *
 * <p>주요 사유: 관리자가 재고 감소를 요청했는데 감소 후 {@code available} 이 음수가 되는 경우.
 * Spring 이 자동으로 409 Conflict 로 매핑한다.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
