package com.shoppinglive.commercecore.sku.service;

import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.service.ProductTradingService;
import com.shoppinglive.commercecore.sales.dto.SalesLookup;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.exception.IllegalStateTransitionException;
import com.shoppinglive.commercecore.sales.exception.SalesAlreadyRegisteredException;
import com.shoppinglive.commercecore.sales.exception.SalesNotFoundException;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.entity.SkuSaleStatus;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기존 단일 판매 API의 SKU 어댑터. 다중 SKU에서 대표값을 임의 선택하지 않는다. */
@Service
public class SkuSalesService {
    private final SkuService skus;
    private final ProductTradingService products;
    private final EntityManager entityManager;

    public SkuSalesService(
            SkuService skus, ProductTradingService products, EntityManager entityManager) {
        this.skus = skus;
        this.products = products;
        this.entityManager = entityManager;
    }

    @Transactional
    public SkuView register(Long productId, long price, int stock, String sellerId) {
        // 기존 productId 등록은 상품 행 배타 잠금으로 중복 요청을 직렬화한다.
        // SKU의 신규 다중 생성 포트와는 구분하며 Product→SKU 잠금 순서를 유지한다.
        var entity = entityManager.find(Product.class, productId);
        if (entity == null) throw new SalesNotFoundException("상품을 찾을 수 없습니다: " + productId);
        // 같은 영속성 컨텍스트의 옛 version으로 잠금부터 요청하지 않도록 최신화와 잠금을 함께 한다.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        var product = products.lockForSkuCreation(productId, sellerId);
        if (!skus.findByProductId(productId).isEmpty())
            throw new SalesAlreadyRegisteredException(productId);
        return skus.create(productId, price, stock, product.name());
    }

    @Transactional
    public SkuView changePrice(Long id, long price, String sellerId) {
        owned(id, sellerId);
        return skus.changePrice(id, price);
    }

    @Transactional
    public SkuView adjustAvailable(Long id, int delta, String sellerId) {
        owned(id, sellerId);
        return skus.adjustAvailable(id, delta);
    }

    @Transactional
    public SkuView changeStatus(Long id, SalesStatus status, String sellerId) {
        if (status == null || !status.isSellerChangeable())
            throw new IllegalStateTransitionException("판매자가 지정할 수 없는 상태입니다: " + status);
        owned(id, sellerId);
        return skus.changeSaleStatus(id, SkuSaleStatus.valueOf(status.name()));
    }

    @Transactional(readOnly = true)
    public SkuView getStock(Long id, String sellerId) {
        SkuView sku = required(id);
        // 읽기에는 거래 잠금을 사용하지 않는다.
        var product = entityManager.find(Product.class, sku.productId());
        if (product == null || product.isDeleted())
            throw new SalesNotFoundException("상품을 찾을 수 없습니다.");
        if (!Objects.equals(sellerId, product.getSellerId()))
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 상품만 조회할 수 있습니다.");
        return sku;
    }

    private void owned(Long id, String sellerId) {
        products.lockForSkuCreation(required(id).productId(), sellerId);
    }

    private SkuView required(Long id) {
        return skus.findBySkuIds(List.of(id)).stream()
                .findFirst()
                .orElseThrow(() -> new SalesNotFoundException("SKU를 찾을 수 없습니다: " + id));
    }

    @Transactional(readOnly = true)
    public List<SalesLookup> findSingleByProductIds(Collection<Long> ids) {
        Map<Long, List<SkuView>> grouped =
                skus.findByProductIds(ids).stream()
                        .collect(Collectors.groupingBy(SkuView::productId));
        if (grouped.values().stream().anyMatch(values -> values.size() > 1))
            throw new BusinessException(ErrorCode.CONFLICT, "다중 SKU 상품은 단일 판매 응답으로 조회할 수 없습니다.");
        return grouped.values().stream()
                .map(values -> values.getFirst())
                .map(
                        sku ->
                                new SalesLookup(
                                        sku.productId(),
                                        sku.skuId(),
                                        sku.price(),
                                        sku.status(),
                                        sku.available()))
                .toList();
    }
}
