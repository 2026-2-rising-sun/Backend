package com.shoppinglive.shopping.product.application;

/**
 * 다른 서비스(Commerce 주문 스냅샷, Live 방송 상품)가 쓰는 상품 요약. 판매·공개 상태와 무관하게 내려준다
 * (판매 1 은 아직 판매정보가 없는 상품을 대상으로 한다).
 *
 * @param mainImageUrl 조회 시점에 만든 URL. 저장소가 바뀌면 값도 바뀌므로 받는 쪽은 저장하지 말고 표시용으로만 쓴다
 */
public record ProductSnapshot(Long id, String name, String mainImageUrl) {
}
