package com.shoppinglive.commerce.sales.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 가격 변경 요청 body.
 *
 * <p>검증 실패는 Spring 이 {@code MethodArgumentNotValidException} → 400 으로 매핑한다.
 */
public record ChangePriceRequest(
    @NotNull @Positive Long price
) {
}
