package com.shoppinglive.notification.inbox.entity;

/**
 * 알림을 눌렀을 때 보낼 곳의 종류. 실제 대상은 {@code linkValue} 가 가리킨다.
 *
 * <p>알림 서비스는 다른 도메인의 URL 을 모른다. 어디로 보낼지는 화면이 이 종류와 값으로 판단한다.
 *
 * <p>값은 쓰는 알림이 생길 때 추가한다. 결제·환불 알림의 주문 상세처럼 아직 만들지 않은 알림의 종류는 미리 넣지 않는다 — 쓰는 곳 없이 이름만 있으면 그게 계약처럼
 * 굳는다.
 */
public enum NotificationLinkType {

    /** 방송. 값은 방송 ID. */
    BROADCAST,

    /** 결제 묶음 상세. 값은 PG-UUID. */
    PAYMENT_GROUP,

    /** 구매자 환불 상세. 값은 PG-UUID/환불요청ID. */
    REFUND,

    /** 쿠폰함. 값은 쿠폰 ID. */
    COUPON,

    /** 상품 상세. 값은 상품 ID. */
    PRODUCT
}
