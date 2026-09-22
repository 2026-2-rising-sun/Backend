package com.shoppinglive.commerce.orders.api;

import com.shoppinglive.commerce.orders.application.OrderCheckoutService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문서 확인 API (주문 1).
 *
 * <p>경로를 {@code /v1/orders/checkout} 으로 두어 주문 조회({@code /v1/orders/{orderNumber}})와
 * 같은 묶음에 둔다. 리터럴 경로가 경로 변수보다 먼저 매칭되므로 충돌하지 않지만, 회귀를 막으려고
 * 라우팅 테스트를 함께 둔다.
 */
@RestController
@RequestMapping("/v1/orders/checkout")
public class OrderCheckoutController {

    /** 1 이상의 정수. 부호·선행 0·소수점을 막고 9 자리로 제한해 {@code int} 범위를 넘지 않게 한다. */
    private static final String POSITIVE_INT = "[1-9][0-9]{0,8}";

    private final OrderCheckoutService orderCheckoutService;

    public OrderCheckoutController(OrderCheckoutService orderCheckoutService) {
        this.orderCheckoutService = orderCheckoutService;
    }

    /**
     * 주문서에 표시할 상품명·단가·수량·총액과 주문 가능 여부를 돌려준다.
     *
     * <p>이 호출은 재고를 잡지 않는다. 최종 확정은 주문 생성(주문 2)에서 다시 한다.
     *
     * <p>수량을 문자열로 받아 직접 해석하는 이유: {@code int} 로 선언하면 {@code abc} 같은 값이
     * {@code MethodArgumentTypeMismatchException} 이 되고, 이 예외는 공통 예외 핸들러의
     * {@code Exception} 처리로 떨어져 400 이 아니라 500 이 된다.
     */
    @GetMapping
    public OrderCheckoutResponse checkout(
        @RequestParam(name = "productId", required = false) Long productId,
        @RequestParam(name = "quantity", required = false) String quantity) {
        return OrderCheckoutResponse.from(
            orderCheckoutService.preview(productId, parseQuantity(quantity)));
    }

    private static int parseQuantity(String quantity) {
        if (quantity == null || !quantity.matches(POSITIVE_INT)) {
            throw new BusinessException(
                ErrorCode.INVALID_REQUEST, "quantity 는 1~999999999 사이의 정수여야 합니다.");
        }
        return Integer.parseInt(quantity);
    }
}
