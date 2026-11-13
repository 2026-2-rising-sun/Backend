package com.shoppinglive.commercecore.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeProductSaleStateRequest(
        @NotNull(message = "필수 값입니다.") Boolean saleEnabled,
        @NotNull(message = "필수 값입니다.") @PositiveOrZero(message = "0 이상이어야 합니다.") Long version) {}
