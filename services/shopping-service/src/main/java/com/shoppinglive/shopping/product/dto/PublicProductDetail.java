package com.shoppinglive.shopping.product.dto;

import com.shoppinglive.shopping.client.commerce.SalesDisplayStatus;
import com.shoppinglive.shopping.client.commerce.SalesInfo;
import com.shoppinglive.shopping.product.entity.Product;

/**
 * 공개 상세에 보여줄 상품 기본정보 + 판매정보 스냅샷. 공개 노출 대상(판매중·품절)만 만들어진다.
 *
 * <p>구매 가능 여부는 상태와 재고를 함께 본다. Commerce 가 아직 ON_SALE 로 두었더라도 재고가 0 이면 수량을 고를 수 없으므로 막는다 (표시 상태 자체는
 * Commerce status 를 그대로 따른다).
 */
public record PublicProductDetail(
        Product product, String mainImageUrl, SalesInfo salesInfo, SalesDisplayStatus salesStatus) {

    public boolean purchasable() {
        return salesStatus.isPurchasable() && salesInfo.available() > 0;
    }

    /** 고를 수 있는 최대 수량. 구매 불가면 0. */
    public int maxQuantity() {
        return purchasable() ? salesInfo.available() : 0;
    }
}
