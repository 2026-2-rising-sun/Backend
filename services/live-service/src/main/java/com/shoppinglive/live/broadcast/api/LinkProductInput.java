package com.shoppinglive.live.broadcast.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LinkProductInput(
    @NotNull(message = "productId는 필수입니다.")
    @Positive(message = "productId는 1 이상이어야 합니다.")
    Long productId,

    @NotNull(message = "expectedVersion은 필수입니다.")
    Long expectedVersion
) {
}
