package com.shoppinglive.commerce.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 회원 주문 요청. 성명·연락처는 배송 정보이며 소유자는 검증된 JWT에서 얻는다. */
public record CreateOrderRequest(
        @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Long productId,
        @NotNull(message = "필수 값입니다.") @Positive(message = "0보다 커야 합니다.") Integer quantity,
        @NotBlank(message = "비어 있을 수 없습니다.") @Size(max = 64, message = "크기는 {min}~{max} 범위여야 합니다.")
                String buyerName,
        @NotBlank(message = "비어 있을 수 없습니다.")
                @Pattern(regexp = "^[0-9-]{9,32}$", message = "연락처는 숫자와 하이픈만 사용할 수 있습니다.")
                String buyerPhone,
        @Positive(message = "0보다 커야 합니다.") Long expectedTotalAmount,
        @Size(max = 64, message = "크기는 {min}~{max} 범위여야 합니다.") String couponId) {}
