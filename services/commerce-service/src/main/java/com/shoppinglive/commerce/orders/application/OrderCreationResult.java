package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.orders.domain.Order;

/**
 * 주문 생성 결과.
 *
 * @param order 주문
 * @param created 이번 요청으로 새로 만들어졌으면 {@code true}. 멱등키로 기존 주문을 그대로
 *     돌려준 경우 {@code false} 다. 컨트롤러가 201 과 200 을 가려 쓰는 데 쓴다 — 아무것도
 *     만들지 않았는데 201 Created 를 돌려주면 거짓말이 된다
 */
public record OrderCreationResult(Order order, boolean created) {

    public static OrderCreationResult created(Order order) {
        return new OrderCreationResult(order, true);
    }

    public static OrderCreationResult replayed(Order order) {
        return new OrderCreationResult(order, false);
    }
}
