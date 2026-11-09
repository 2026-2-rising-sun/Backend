package com.shoppinglive.commercecore.order.dto;

public record CartOrderCommand(String buyerName, String buyerPhone, Long expectedTotalAmount) {}
