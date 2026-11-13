package com.shoppinglive.commercecore.cart.dto;

/** 현재 SKU 정보는 표시용이며 주문서 생성 시 거래 원본을 다시 검증한다. */
public record CartSkuView(
        Long id,
        Long skuId,
        int quantity,
        Long version,
        Long unitPrice,
        Integer available,
        String skuStatus,
        boolean purchasable) {}
