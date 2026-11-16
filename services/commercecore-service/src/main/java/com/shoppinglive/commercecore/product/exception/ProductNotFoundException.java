package com.shoppinglive.commercecore.product.exception;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/** Core 상품 원본이 존재하지 않을 때 404로 응답한다. 저장소 장애와 구분한다. */
public class ProductNotFoundException extends BusinessException {

    public ProductNotFoundException(Long productId) {
        super(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: productId=" + productId);
    }
}
