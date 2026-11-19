package com.shoppinglive.commercecore.product.dto;

import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import java.util.List;

/** 최초 등록 결과를 저장해 재시도 때 상품의 후속 변경과 무관하게 재응답한다. */
public record ProductBundleResponse(
        Long productId,
        Long mainImageId,
        String mainImageUrl,
        boolean saleEnabled,
        List<RegisteredSku> skus) {
    public record RegisteredSku(
            Long skuId,
            String optionLabel,
            long price,
            int available,
            int reserved,
            SalesStatus status) {}
}
