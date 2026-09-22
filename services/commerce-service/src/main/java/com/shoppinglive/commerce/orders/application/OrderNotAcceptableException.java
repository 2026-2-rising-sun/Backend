package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 신규 주문을 받을 수 없는 판매 상태에 주문을 시도했을 때 던지는 예외 (409).
 *
 * <p>판매 준비·품절·비공개가 해당한다. 화면에 아직 "구매하기" 가 보이고 있었더라도 실제 상태로
 * 막는다 — 명세 판매 4 의 "신규 주문은 화면 표시와 관계없이 실제 상태로 막는다".
 *
 * <p>이미 만들어진 주문에는 영향이 없다. 비공개로 바뀌어도 결제 대기 중인 주문은 그대로
 * 진행된다.
 */
public class OrderNotAcceptableException extends BusinessException {

    public OrderNotAcceptableException(SalesStatus status) {
        super(ErrorCode.CONFLICT, switch (status) {
            case SOLD_OUT -> "품절된 상품입니다.";
            case PRIVATE, READY -> "현재 판매 중인 상품이 아닙니다.";
            default -> "주문할 수 없는 상태입니다: " + status;
        });
    }
}
