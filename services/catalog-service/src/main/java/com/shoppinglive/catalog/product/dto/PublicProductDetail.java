package com.shoppinglive.catalog.product.dto;

import com.shoppinglive.catalog.client.commercecore.SalesDisplayStatus;
import com.shoppinglive.catalog.client.commercecore.SalesInfo;
import com.shoppinglive.catalog.product.entity.Product;

/**
 * 공개 상세에 보여줄 상품 기본정보 + 판매정보 스냅샷. 공개 노출 대상(판매중·품절)만 만들어진다.
 *
 * <p>품절·수량은 지연된 표시 정보다. 공개 판매 상태이면 주문 화면 진입을 허용하고 Core에서 최신 재고를 검증한다.
 */
public record PublicProductDetail(
        Product product, String mainImageUrl, SalesInfo salesInfo, SalesDisplayStatus salesStatus) {

    public boolean purchasable() {
        return product.isSaleEnabled() && salesStatus.isPubliclyVisible();
    }

    /** 수량 입력 형식의 상한. 실제 재고 상한은 Core 주문 생성에서 검증한다. */
    public int maxQuantity() {
        return purchasable() ? 999999999 : 0;
    }
}
