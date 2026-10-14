package com.shoppinglive.common.web;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/** 두 구매 API가 사용하는 동일한 문자열 수량 형식. 도메인별 구매 제한은 각 서비스가 판단한다. */
public final class QuantityParser {
    private QuantityParser() {}

    public static int parse(String quantity) {
        if (quantity == null || !quantity.matches("[1-9][0-9]{0,8}")) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "quantity 는 1~999999999 사이의 정수여야 합니다.");
        }
        return Integer.parseInt(quantity);
    }
}
