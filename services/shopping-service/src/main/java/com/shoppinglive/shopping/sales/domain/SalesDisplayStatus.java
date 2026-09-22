package com.shoppinglive.shopping.sales.domain;

/**
 * 상품 화면(관리자 목록·공개 목록·공개 상세)에 보여줄 판매 상태.
 *
 * <p>{@link SalesStatus} 에 두 가지를 더한다. {@link #NOT_REGISTERED} 는 판매정보가 아직 없는 상품,
 * {@link #UNKNOWN} 은 Commerce 조회 실패다. UNKNOWN 을 품절로 보여주면 사용자가 잘못 판단하므로
 * {@link #SOLD_OUT} 으로 대체하지 않는다. 품절 판정은 Commerce status 를 따르고 재고 수량으로 다시 하지 않는다.
 */
public enum SalesDisplayStatus {
    NOT_REGISTERED,
    READY,
    ON_SALE,
    SOLD_OUT,
    PRIVATE,
    UNKNOWN;

    /** @param salesInfo {@code null} 이면 미등록 (조회 실패가 아님) */
    public static SalesDisplayStatus from(SalesInfo salesInfo) {
        if (salesInfo == null) {
            return NOT_REGISTERED;
        }
        return switch (salesInfo.status()) {
            case READY -> READY;
            case ON_SALE -> ON_SALE;
            case SOLD_OUT -> SOLD_OUT;
            case PRIVATE -> PRIVATE;
        };
    }

    /** 판매정보 조회에 실패했을 때. */
    public static SalesDisplayStatus unknown() {
        return UNKNOWN;
    }

    /** 공개 목록·상세 노출 여부. 품절은 노출하되 구매만 막는다. */
    public boolean isPubliclyVisible() {
        return this == ON_SALE || this == SOLD_OUT;
    }

    public boolean isPurchasable() {
        return this == ON_SALE;
    }
}
