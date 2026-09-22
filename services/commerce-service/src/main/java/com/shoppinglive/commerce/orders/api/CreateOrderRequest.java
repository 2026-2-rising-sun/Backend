package com.shoppinglive.commerce.orders.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 주문 생성 요청 body (주문 2).
 *
 * <p>P1 은 비회원 주문이라 회원정보 대신 이름·연락처·조회 비밀번호를 받는다. 배송지·배송비·
 * 할인은 P1 범위가 아니다.
 *
 * @param productId Shopping 상품 식별자. 구매자는 상품 페이지에서 넘어오므로 salesId 는 모른다
 * @param quantity 주문 수량 (1 이상)
 * @param buyerName 주문자 이름
 * @param buyerPhone 주문자 연락처. 형식만 확인하며 본인인증이 아니다
 * @param lookupPassword 주문 조회 비밀번호. 주문번호와 함께 조회·결제·취소에 쓴다. 평문으로
 *     저장하지 않고 bcrypt hash 만 보관한다
 * @param expectedTotalAmount 주문서에서 사용자에게 보여준 총액. <b>선택 항목</b>이며 보내면
 *     서버가 계산한 금액과 비교한다. 다르면 주문을 만들지 않고 409 로 알려주어, 사용자가 본 적
 *     없는 금액이 조용히 결제되는 것을 막는다
 */
public record CreateOrderRequest(
    @NotNull Long productId,
    @NotNull @Positive Integer quantity,
    @NotBlank @Size(max = 64) String buyerName,
    @NotBlank @Pattern(
        regexp = "^[0-9-]{9,32}$",
        message = "연락처는 숫자와 하이픈만 사용할 수 있습니다.") String buyerPhone,
    @NotBlank @Size(min = 4, max = 64) String lookupPassword,
    @Positive Long expectedTotalAmount
) {
}
