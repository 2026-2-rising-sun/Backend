package com.shoppinglive.commerce.sales.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 판매정보 최초 설정 요청 body (판매 1).
 *
 * <p>P1 명세 "기본 입력 제약": 가격은 양의 정수 원 단위, 재고는 0 이상 정수. 검증 실패는 Spring
 * 이 {@code MethodArgumentNotValidException} → 400 으로 매핑한다.
 *
 * @param productId Shopping 서비스의 상품 식별자. 존재 여부는 서비스 계층이 Shopping 에 확인한다
 * @param price 판매가 (원)
 * @param initialStock 초기 판매 가능 재고. 0 으로 등록한 뒤 판매 3 으로 채워도 된다
 */
public record RegisterSalesRequest(
    @NotNull Long productId,
    @NotNull @Positive Long price,
    @NotNull @PositiveOrZero Integer initialStock
) {
}
