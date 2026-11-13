package com.shoppinglive.commercecore.cart.dto;

/** 영속 엔티티를 외부 모듈에 전달하지 않는 선택 항목 조회 결과. */
public record SelectedCartItem(
        Long itemId, Long skuId, Long productId, int quantity, Long version) {}
