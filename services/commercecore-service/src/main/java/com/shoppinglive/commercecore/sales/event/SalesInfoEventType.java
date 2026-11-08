package com.shoppinglive.commercecore.sales.event;

/** Commerce 판매정보 발행자와 Shopping 소비자가 사용하는 이벤트 이름. */
public enum SalesInfoEventType {
    SalesRegistered,
    PriceChanged,
    InventoryChanged,
    SalesStatusChanged
}
