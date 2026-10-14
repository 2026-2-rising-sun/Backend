package com.shoppinglive.shopping.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 길이 규칙은 공백 제거 후 기준이라 서비스가 검사한다. 여기서는 누락만 막는다. */
public record RegisterProductRequest(
        @NotBlank(message = "비어 있을 수 없습니다.") String name,
        @NotBlank(message = "비어 있을 수 없습니다.") String description,
        @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Long mainImageId) {

    public RegisterProductCommand toCommand() {
        return new RegisterProductCommand(name, description, mainImageId);
    }
}
