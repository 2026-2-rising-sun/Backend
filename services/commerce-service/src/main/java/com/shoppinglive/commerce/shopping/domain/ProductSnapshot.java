package com.shoppinglive.commerce.shopping.domain;

/**
 * shopping-service 상품의 스냅샷.
 *
 * <p>주문 생성 시점(주문 2)에 조회해 {@code orders.product_name_snapshot} 등 주문 관련
 * 컬럼에 보존한다. shopping 이 나중에 상품명·이미지를 바꿔도 이미 만들어진 주문은 이
 * 스냅샷 값 그대로 유지한다.
 *
 * @param id 상품 식별자
 * @param name 상품 표시명 (주문 스냅샷용)
 * @param mainImageUrl 대표 이미지 조회 경로. nullable — 이미지 미등록 상품도 존재 가능
 */
public record ProductSnapshot(Long id, String name, String mainImageUrl) {
}
