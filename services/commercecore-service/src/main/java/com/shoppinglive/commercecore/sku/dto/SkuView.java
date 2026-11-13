package com.shoppinglive.commercecore.sku.dto;

import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sku.entity.SkuSaleStatus;

/** 가격은 KRW 1원 단위다. 이 조회만으로 재고를 예약하지 않는다. */
public record SkuView(
        Long skuId,
        Long productId,
        long price,
        int available,
        int reserved,
        SkuSaleStatus saleStatus,
        long version) {

    /** 기존 외부 enum을 유지하면서 판매 중인 0재고만 품절로 표시한다. */
    public SalesStatus status() {
        if (saleStatus == SkuSaleStatus.ON_SALE && available == 0) return SalesStatus.SOLD_OUT;
        return SalesStatus.valueOf(saleStatus.name());
    }
}
