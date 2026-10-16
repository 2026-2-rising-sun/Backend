package com.shoppinglive.commerce.sales.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 재고 조정 요청 body.
 *
 * <p>{@code delta} 양수는 재고 추가, 음수는 감소. 0 은 허용하지만 무해한 no-op 이 된다 (Service 계층에서 별도 예외 처리 없이 조건부 UPDATE
 * 가 1 을 반환).
 *
 * <p>{@code @Positive(message = "0보다 커야 합니다.")} 를 걸지 않는 이유: 음수 delta 가 판매자 재고 감소의 정상 사용례.
 */
public record AdjustStockRequest(@NotNull(message = "필수 값입니다.") Integer delta) {}
