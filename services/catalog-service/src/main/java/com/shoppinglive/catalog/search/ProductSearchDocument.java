package com.shoppinglive.catalog.search;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ES 상품 문서. 검색, 필터, 정렬에 쓰는 값만 담는다.
 *
 * <p>price 는 대표 가격이다. 판매 중 SKU 최저가이고, 모두 품절이면 품절 SKU 최저가다. 재고 수량과 쿠폰은 넣지 않는다. 재고는 자주 바뀌고, 쿠폰은 시작,
 * 종료 시각에 이벤트가 없어 문서가 틀어지기 때문이다. 둘 다 검색 응답 때 Catalog DB 에서 붙인다.
 */
public record ProductSearchDocument(
        long productId,
        String name,
        String description,
        String category,
        String sellerId,
        long price,
        boolean soldOut,
        Instant createdAt) {

    public String status() {
        return soldOut ? "SOLD_OUT" : "ON_SALE";
    }

    Map<String, Object> source() {
        Map<String, Object> source = new HashMap<>();
        source.put("productId", productId);
        source.put("name", name);
        source.put("description", description);
        source.put("category", category);
        source.put("sellerId", sellerId);
        source.put("price", price);
        source.put("soldOut", soldOut);
        source.put("status", status());
        source.put("createdAt", createdAt.toString());
        return source;
    }
}
