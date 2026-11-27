package com.shoppinglive.catalog.product.service;

public enum ProductSort {
    LATEST,
    PRICE_ASC,
    PRICE_DESC,
    /** 최근 7일 상품 상세 조회수. 1시간마다 다시 계산한다. */
    RANKING
}
