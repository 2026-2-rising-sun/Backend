package com.shoppinglive.shopping.sales.domain;

/**
 * Commerce 판매 상태 ({@code sales_info.status}). Commerce enum 을 import 하지 않고 자체 정의해
 * 두 서비스가 HTTP 계약의 문자열 값으로만 결합하게 한다.
 */
public enum SalesStatus {
    READY,
    ON_SALE,
    SOLD_OUT,
    PRIVATE
}
