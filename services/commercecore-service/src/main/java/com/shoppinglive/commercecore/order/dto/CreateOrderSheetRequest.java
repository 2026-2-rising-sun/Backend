package com.shoppinglive.commercecore.order.dto;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CreateOrderSheetRequest(
        List<@NotNull @Valid Item> items,
        List<@NotNull @Valid CartSelection> cartItems,
        @NotBlank @Size(max = 64) String buyerName,
        @NotBlank @Size(max = 32) String buyerPhone) {
    public record Item(@NotNull @Positive Long skuId, @Positive int quantity) {}
}
