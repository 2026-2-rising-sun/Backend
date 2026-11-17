package com.shoppinglive.commercecore.sku.entity;

/** 품절과 독립적으로 보존하는 판매자의 판매 의도. READY는 최초 생성에만 쓴다. */
public enum SkuSaleStatus {
    READY,
    ON_SALE,
    PRIVATE
}
