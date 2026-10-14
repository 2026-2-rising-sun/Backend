package com.shoppinglive.shopping.sales.event;

/** contracts/events/commerce-sales-info.yaml에 정의된 소비측 전송 상수. */
public final class SalesInfoEventContract {
    public static final String TOPIC_NAME = "commerce.sales-info.v1";
    public static final int SCHEMA_VERSION = 1;

    private SalesInfoEventContract() {}
}
