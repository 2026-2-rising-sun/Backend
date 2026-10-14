package com.shoppinglive.commerce.sales.event;

/** 서비스 간 의존성을 피하기 위해 계약에 정의한 Commerce의 네 가지 판매 상태를 별도로 표현한다. */
public enum SalesInfoStatus {
    READY,
    ON_SALE,
    SOLD_OUT,
    PRIVATE
}
