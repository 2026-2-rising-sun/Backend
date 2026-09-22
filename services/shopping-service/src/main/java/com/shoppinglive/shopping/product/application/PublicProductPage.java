package com.shoppinglive.shopping.product.application;

import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.List;

/**
 * 공개 목록 한 페이지.
 *
 * @param next    다음 조회 시작 위치. {@code hasNext} 가 false 면 {@code null}
 * @param hasNext 뒤에 공개 상품이 더 있을 수 있음. 이번 페이지가 비어 있어도 true 일 수 있다
 */
public record PublicProductPage(List<Entry> entries, ProductListPosition next, boolean hasNext) {

    /** @param status 항상 공개 대상(ON_SALE·SOLD_OUT) */
    public record Entry(Product product, SalesInfo salesInfo, SalesDisplayStatus status) {
    }
}
