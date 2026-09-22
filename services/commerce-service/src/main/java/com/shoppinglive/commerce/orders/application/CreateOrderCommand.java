package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 주문 생성 입력 (주문 2).
 *
 * <p>API DTO 와 분리한 이유: 서비스는 HTTP 를 몰라야 테스트하기 쉽고, 나중에 방송 경유 주문처럼
 * 다른 진입점이 생겨도 같은 유스케이스를 재사용할 수 있다.
 *
 * @param expectedTotalAmount 주문서에서 사용자에게 보여준 총액. 선택 항목이라 {@code null} 일 수
 *     있고, 그때는 금액 확인을 건너뛴다
 */
public record CreateOrderCommand(
    Long productId,
    Integer quantity,
    String buyerName,
    String buyerPhone,
    String lookupPassword,
    Long expectedTotalAmount
) {

    /**
     * 컨트롤러의 Bean Validation 과 별개로 서비스 진입점에서도 확인한다. 다른 진입점이 생기거나
     * 서비스를 직접 호출하는 테스트에서도 같은 제약이 걸리게 하기 위함이다.
     */
    public void validate() {
        require(productId != null, "productId 는 필수입니다.");
        require(quantity != null && quantity >= 1, "수량은 1 이상이어야 합니다.");
        require(hasText(buyerName), "주문자 이름은 필수입니다.");
        require(hasText(buyerPhone), "주문자 연락처는 필수입니다.");
        require(hasText(lookupPassword), "주문 조회 비밀번호는 필수입니다.");
        require(expectedTotalAmount == null || expectedTotalAmount > 0,
            "확인 금액은 양수여야 합니다.");
    }

    /**
     * 사용자가 확인한 금액과 지금 계산된 금액이 같은지 본다.
     *
     * @throws OrderAmountMismatchException 두 금액이 다를 때. 사용자가 본 적 없는 금액으로
     *     주문이 만들어지지 않게 막는다
     */
    public void verifyExpectedAmount(long actualTotalAmount) {
        if (expectedTotalAmount != null && expectedTotalAmount != actualTotalAmount) {
            throw new OrderAmountMismatchException(expectedTotalAmount, actualTotalAmount);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
    }
}
