package com.shoppinglive.shopping.product.api;

import com.shoppinglive.shopping.product.application.RegisterProductCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 길이 규칙은 공백 제거 후 기준이라 서비스가 검사한다. 여기서는 누락만 막는다. */
public record RegisterProductRequest(@NotBlank String name, @NotBlank String description,
        @NotNull @Positive Long mainImageId) {

    RegisterProductCommand toCommand() {
        return new RegisterProductCommand(name, description, mainImageId);
    }
}
