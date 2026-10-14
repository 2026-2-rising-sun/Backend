package com.shoppinglive.commerce.order.dto;

public record CartOrderCommand(String buyerName, String buyerPhone, Long expectedTotalAmount) {}
