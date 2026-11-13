package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.product.service.ProductTradingService;
import com.shoppinglive.commercecore.sales.exception.SalesNotFoundException;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 구 productId API의 Sales 식별자는 보존하되 거래 값은 SKU 원본에서 읽는다. */
@Service
public class LegacyOrderSkuService {
    private final SalesJpaRepository identities;
    private final SkuService skus;
    private final ProductTradingService products;

    public LegacyOrderSkuService(
            SalesJpaRepository identities, SkuService skus, ProductTradingService products) {
        this.identities = identities;
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
        long id =
                identities
                        .findByProductId(productId)
                        .orElseThrow(
                                () ->
                                        new SalesNotFoundException(
                                                "상품의 기존 판매 식별자가 없습니다. SKU 주문서를 사용해 주세요."))
                        .getId();
        if (selectedSkuId != null && selectedSkuId != id)
            throw new BusinessException(ErrorCode.CONFLICT, "옵션 SKU는 /v1/order-sheets로 주문해 주세요.");
        return id;
    }
}
