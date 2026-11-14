package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.product.service.ProductTradingService;
import com.shoppinglive.commercecore.sales.exception.SalesNotFoundException;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 구 productId API는 SKU 하나인 상품에 한해 호환하며 거래 값은 SKU 원본에서 읽는다. */
@Service
public class LegacyOrderSkuService {
    private final SkuService skus;
    private final ProductTradingService products;

    public LegacyOrderSkuService(SkuService skus, ProductTradingService products) {
        this.skus = skus;
        this.products = products;
    }

    public SkuView read(Long productId, Long selectedSkuId) {
        long id = identity(productId, selectedSkuId);
        return skus.findBySkuIds(List.of(id)).stream()
                .filter(s -> s.productId().equals(productId))
                .findFirst()
                .orElseThrow(() -> new SalesNotFoundException("기본 SKU를 찾을 수 없습니다."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<SkuView> lockForNewOrder(Map<Long, Long> selectedByProduct) {
        var ids = selectedByProduct.keySet().stream().sorted().toList();
        products.lockForNewOrder(ids);
        var skuIds = ids.stream().map(id -> identity(id, selectedByProduct.get(id))).toList();
        var locked = skus.lockForOrder(skuIds);
        for (var sku : locked)
            if (!selectedByProduct.containsKey(sku.productId()))
                throw new BusinessException(ErrorCode.CONFLICT, "기본 SKU와 상품 참조가 다릅니다.");
        return locked;
    }

    private long identity(Long productId, Long selectedSkuId) {
        var candidates = skus.findByProductId(productId);
        if (candidates.isEmpty()) throw new SalesNotFoundException("상품의 SKU가 없습니다.");
        if (candidates.size() != 1)
            throw new BusinessException(
                    ErrorCode.CONFLICT, "여러 SKU 상품은 /v1/order-sheets로 주문해 주세요.");
        long id = candidates.getFirst().skuId();
        if (selectedSkuId != null && selectedSkuId != id)
            throw new BusinessException(ErrorCode.CONFLICT, "옵션 SKU는 /v1/order-sheets로 주문해 주세요.");
        return id;
    }
}
