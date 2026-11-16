package com.shoppinglive.commercecore.cart.exception;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

public class CartItemNotFoundException extends BusinessException {
    public CartItemNotFoundException() {
        super(ErrorCode.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다.");
    }
}
