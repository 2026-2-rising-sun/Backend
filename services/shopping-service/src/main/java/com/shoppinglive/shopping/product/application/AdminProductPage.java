package com.shoppinglive.shopping.product.application;

import java.util.List;

/** 관리용 목록 페이지. 상품이 없으면 items 가 빈 정상 응답이다 (판매정보 조회 실패와 구분). */
public record AdminProductPage(List<AdminProductSummary> items, int page, int size, long totalElements,
        int totalPages) {
}
