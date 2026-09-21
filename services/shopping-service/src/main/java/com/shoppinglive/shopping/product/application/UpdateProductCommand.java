package com.shoppinglive.shopping.product.application;

/**
 * 상품 기본정보 부분 수정 입력. null 인 항목은 그대로 둔다.
 *
 * @param version 클라이언트가 마지막으로 본 버전. 현재 버전과 다르면 덮어쓰지 않고 409
 */
public record UpdateProductCommand(String name, String description, Long mainImageId, Long version) {
}
