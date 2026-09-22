package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.product.application.UpdateProductCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 생략(null)한 항목은 그대로 둔다. 길이 규칙은 공백 제거 후 기준이라 서비스가 검사한다. */
public record UpdateProductRequest(String name, String description, @Positive Long mainImageId,
        @NotNull Long version) {

    UpdateProductCommand toCommand() {
        return new UpdateProductCommand(name, description, mainImageId, version);
    }
}
