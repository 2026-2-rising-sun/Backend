package com.shoppinglive.live.promotion.entity;

/**
 * 프로모션이 CLOSED 가 된 이유. 소비자(커머스·알림)가 UX 를 다르게 쓸 수 있어 쪼갠다.
 *
 * <ul>
 *   <li>{@link #SELLER_CLOSED} — 판매자가 수동으로 close 를 호출함
 *   <li>{@link #EXPIRED} — duration_seconds 가 지나 스케줄러가 끔
 *   <li>{@link #BROADCAST_ENDED} — 방송이 종료되어 라이프사이클 상 끌려 닫힘
 *   <li>{@link #CANCELED} — READY 상태에서 열리지 않고 취소됨
 * </ul>
 */
public enum PromotionCloseReason {
    SELLER_CLOSED,
    EXPIRED,
    BROADCAST_ENDED,
    CANCELED
}
