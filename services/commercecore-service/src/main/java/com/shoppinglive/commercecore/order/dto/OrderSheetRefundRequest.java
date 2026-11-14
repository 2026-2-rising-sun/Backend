package com.shoppinglive.commercecore.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record OrderSheetRefundRequest(@NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) {
    public record Item(@NotNull @Positive Long orderItemId, @Positive int quantity) {}
}
