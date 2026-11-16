package com.shoppinglive.commercecore.cart.dto;

import java.util.List;

public record CartProductGroup(
        Long productId,
        String productName,
        String mainImageUrl,
        boolean saleEnabled,
        List<CartSkuView> items) {
    public CartProductGroup {
        items = List.copyOf(items);
    }
}
