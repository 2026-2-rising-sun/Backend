package com.shoppinglive.shopping.product.api;

import java.util.List;

/**
 * 공개 목록 응답. 목록의 끝은 {@code hasNext=false} 로만 판단한다 — 비공개 상품이 길게 이어지면
 * {@code items} 가 비어 있어도 {@code hasNext=true} 일 수 있다.
 *
 * @param nextCursor 다음 요청의 {@code cursor}. {@code hasNext} 가 false 면 {@code null}
 */
public record PublicProductListResponse(List<PublicProductItemResponse> items, String nextCursor, boolean hasNext) {
}
