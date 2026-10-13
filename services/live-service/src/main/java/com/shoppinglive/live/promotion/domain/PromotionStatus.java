package com.shoppinglive.live.promotion.domain;

/**
 * 방송 중 이벤트(프로모션)의 상태.
 *
 * <p>생성 직후 {@link #READY}. 판매자가 열면 {@link #OPEN}. 어떤 이유로든 끝나면 {@link #CLOSED}.
 * CLOSED 에서 다시 OPEN 으로는 가지 않는다. READY 에서 바로 CLOSED 로는 갈 수 있다(취소·방송 종료).
 */
public enum PromotionStatus {
    READY,
    OPEN,
    CLOSED
}
