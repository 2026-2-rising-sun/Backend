package com.shoppinglive.commercecore.product.dto;

/** 호출자 트랜잭션에서 잠금·검증한 상품 값. SKU 가격/수량은 포함하지 않는다. */
public record ProductTradeSnapshot(
        Long productId, String name, String sellerId, String mainImageUrl, long version) {}
