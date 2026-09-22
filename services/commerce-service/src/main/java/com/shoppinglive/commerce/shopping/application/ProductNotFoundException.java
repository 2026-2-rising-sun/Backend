package com.shoppinglive.commerce.shopping.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * Shopping 에 해당 상품이 없을 때 던지는 예외.
 *
 * <p>{@link ShoppingUnavailableException} 과 반드시 구분한다. 이 예외는 "물어봤고, 없다는 답을
 * 확실히 받았다" 는 뜻이고, 저쪽은 "물어보지 못했다" 는 뜻이다. P1 명세가 여러 곳에서 요구하는
 * "연동 실패와 실제 데이터 없음을 구분한다" 가 이 두 예외의 분리로 표현된다. 둘을 뭉뚱그리면
 * Shopping 이 잠깐 죽었을 때 멀쩡한 상품을 "없는 상품" 으로 단정하게 된다.
 */
public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(Long productId) {
        super(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: productId=" + productId);
    }
}
