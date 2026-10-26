package com.shoppinglive.shopping.product.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

/** 1단계 상품 분류. 상품에 값이 없으면(null) 미분류다. */
public enum ProductCategory {
    FASHION,
    BEAUTY,
    FOOD,
    LIVING,
    DIGITAL,
    KIDS,
    ETC;

    /** Jackson 기본 enum 해석은 0·"0" 같은 순번도 받으므로 JSON 본문은 이름만 허용한다. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static ProductCategory fromName(String name) {
        return valueOf(name);
    }
}
