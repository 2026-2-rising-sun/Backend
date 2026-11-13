package com.shoppinglive.commercecore.cart.dto;

/** 주문 생성 때 선택한 항목과 당시 버전. */
public record CartSelection(Long itemId, Long version) {}
