package com.shoppinglive.commercecore.product.dto;

import com.shoppinglive.commercecore.product.entity.ProductCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record RegisterProductBundleRequest(
        @NotBlank(message = "상품명은 필수입니다.") String name,
        @NotBlank(message = "상품 설명은 필수입니다.") String description,
        ProductCategory category,
        @NotEmpty(message = "SKU는 하나 이상 필요합니다.") @Size(max = 100, message = "SKU는 최대 100개입니다.")
                List<@NotNull(message = "SKU 항목은 필수입니다.") @Valid InitialSku> skus) {
    public record InitialSku(
            String optionLabel,
            @NotNull(message = "가격은 필수입니다.") @Positive(message = "가격은 1원 이상이어야 합니다.") Long price,
            @NotNull(message = "초기 재고는 필수입니다.") @PositiveOrZero(message = "초기 재고는 0 이상이어야 합니다.")
                    Integer initialStock) {}
}
