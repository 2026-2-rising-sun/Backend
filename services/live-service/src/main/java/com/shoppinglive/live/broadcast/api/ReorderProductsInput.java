package com.shoppinglive.live.broadcast.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ReorderProductsInput(
    @NotEmpty(message = "linkIds는 전체 연결 상품의 순열이어야 합니다.")
    List<Long> linkIds,

    @NotNull(message = "expectedVersion은 필수입니다.")
    Long expectedVersion
) {
}
