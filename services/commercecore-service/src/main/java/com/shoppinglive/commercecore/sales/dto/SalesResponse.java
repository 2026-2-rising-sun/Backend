package com.shoppinglive.commercecore.sales.dto;

import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;

/**
 * 판매정보 API 응답 DTO. 판매자 화면에 필요한 최소 필드만 노출한다.
 *
 * <p>{@code version} 은 현재 저장 버전이다. 가격 변경 API는 클라이언트 버전을 받지 않으며, SKU 행 잠금으로 서버 트랜잭션을 직렬화한다.
 */
public record SalesResponse(Long id, Long productId, Long price, SalesStatus status, Long version) {

    public static SalesResponse from(com.shoppinglive.commercecore.sku.dto.SkuView sku) {
        return new SalesResponse(
                sku.skuId(), sku.productId(), sku.price(), sku.status(), sku.version());
    }

    public static SalesResponse from(Sales sales) {
        return new SalesResponse(
                sales.getId(),
                sales.getProductId(),
                sales.getPrice(),
                sales.getStatus(),
                sales.getVersion());
    }
}
