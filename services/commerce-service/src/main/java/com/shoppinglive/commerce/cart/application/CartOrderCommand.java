package com.shoppinglive.commerce.cart.application;

public record CartOrderCommand(String buyerName, String buyerPhone, Long expectedTotalAmount) {}
