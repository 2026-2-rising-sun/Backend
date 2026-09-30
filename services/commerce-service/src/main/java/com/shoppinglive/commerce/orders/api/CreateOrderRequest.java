package com.shoppinglive.commerce.orders.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 회원 주문 요청. 성명·연락처는 배송 정보이며 소유자는 검증된 JWT에서 얻는다. */
public record CreateOrderRequest(
    @NotNull @Positive Long productId,
    @NotNull @Positive Integer quantity,
    @NotBlank @Size(max = 64) String buyerName,
    @NotBlank @Pattern(
        regexp = "^[0-9-]{9,32}$",
        message = "연락처는 숫자와 하이픈만 사용할 수 있습니다.") String buyerPhone,
    @Positive Long expectedTotalAmount
) {
}
