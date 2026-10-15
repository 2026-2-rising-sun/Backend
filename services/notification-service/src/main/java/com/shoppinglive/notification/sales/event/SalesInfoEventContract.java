package com.shoppinglive.notification.sales.event;

/**
 * Commerce 판매정보 이벤트 계약 중 알림이 쓰는 부분의 사본.
 *
 * <p>서비스끼리는 코드를 의존하지 않으므로(모듈 경계) 소비자도 계약을 따로 들고 있는다. Shopping 도 같은 토픽을 구독하면서 자기 사본을 들고 있다.
 *
 * <p>payload 는 변경분이 아니라 <b>그 상품 판매정보의 현재 전체</b>다. 그래서 {@code eventType} 을 보지 않는다 — 판매 등록이든 재고 변경이든
 * 같은 방식으로 저장하면 된다. 종류가 늘어도 알림은 영향을 받지 않는다.
 */
public final class SalesInfoEventContract {

    public static final String TOPIC_NAME = "commerce.sales-info.v1";
    public static final String SOURCE = "commerce-service";
    public static final int SCHEMA_VERSION = 1;

    /** 판매 중. 품절에서 이 상태로 바뀌면 재입고다. */
    public static final String ON_SALE = "ON_SALE";

    /** 품절. 재입고 알림을 신청할 수 있는 상태다. */
    public static final String SOLD_OUT = "SOLD_OUT";

    /** 사본 컬럼 길이. 이보다 긴 값이 오면 저장하지 않고 건너뛴다. */
    public static final int STATUS_MAX_LENGTH = 16;

    private SalesInfoEventContract() {}
}
