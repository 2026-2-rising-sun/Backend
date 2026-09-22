package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 주문서에서 보여준 금액과 주문 생성 시점의 금액이 다를 때 던지는 예외 (409).
 *
 * <p>주문서를 열어둔 사이 관리자가 가격을 바꾸면 (판매 2) 생길 수 있다. 이때 선택지는 셋인데
 * 둘은 문제가 있다.
 * <ul>
 *   <li>예전 금액으로 주문 — 현재 판매가가 아닌 값에 팔린다</li>
 *   <li>새 금액으로 조용히 주문 — <b>사용자가 본 적 없는 금액이 결제된다</b></li>
 *   <li>새 금액을 알려주고 다시 확인받는다 — 이 예외가 하는 일</li>
 * </ul>
 *
 * <p>P1 명세 주문 2 "금액을 임의로 올려 결제하지 않는다" 와 판매 2 완료 기준 "이전 가격을 보던
 * 사람이 변경 금액을 확인하지 않은 채 결제되지 않는다" 를 함께 만족시킨다.
 *
 * <p>응답 본문에는 금액을 구조화해 담지 않는다. 클라이언트는 이 409 를 받으면 주문서 조회
 * (주문 1) 를 다시 호출해 최신 금액을 받아 사용자에게 보여주면 된다.
 */
public class OrderAmountMismatchException extends BusinessException {

    public OrderAmountMismatchException(long expectedAmount, long actualAmount) {
        super(ErrorCode.CONFLICT,
            "주문 금액이 변경되었습니다. 확인한 금액=%d원, 현재 금액=%d원. 최신 금액을 확인한 뒤 다시 주문해 주세요."
                .formatted(expectedAmount, actualAmount));
    }
}
