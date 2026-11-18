package com.shoppinglive.commercecore.sales.dto;

import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sku.dto.SkuView;

/** 판매정보 API 응답 DTO. 가격·상태 변경은 요청 버전 없이 SKU 행 잠금으로 직렬화한다. */
public record SalesResponse(Long id, Long productId, Long price, SalesStatus status) {

    public static SalesResponse from(SkuView sku) {
        return new SalesResponse(sku.skuId(), sku.productId(), sku.price(), sku.status());
    }

    public static SalesResponse from(Sales sales) {
        return new SalesResponse(
                sales.getId(), sales.getProductId(), sales.getPrice(), sales.getStatus());
    }
}
