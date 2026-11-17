package com.shoppinglive.commercecore.sku.service;

import com.shoppinglive.commercecore.sales.exception.InsufficientStockException;
import com.shoppinglive.commercecore.sales.exception.SalesNotFoundException;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.entity.SkuSaleStatus;
import com.shoppinglive.commercecore.sku.event.SkuEventTracker;
import com.shoppinglive.commercecore.sku.repository.SkuRepository;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkuService {
    private final SkuRepository repository;
    private final SkuEventTracker events;

    public SkuService(SkuRepository repository, SkuEventTracker events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<SkuView> findBySkuIds(Collection<Long> skuIds) {
        return repository.findBySkuIds(sortedIds(skuIds), false);
    }

    @Transactional(readOnly = true)
    public List<SkuView> findByProductId(Long productId) {
        requireId(productId);
        return repository.findByProductId(productId);
    }

    @Transactional(readOnly = true)
    public List<SkuView> findByProductIds(Collection<Long> productIds) {
        return repository.findByProductIds(sortedIds(productIds));
    }

    /** 호출자가 Product를 ID순 공유 잠금·검증한 뒤 SKU를 ID순으로 잠근다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<SkuView> lockForOrder(Collection<Long> skuIds) {
        List<Long> ids = sortedIds(skuIds);
        List<SkuView> views = repository.findBySkuIds(ids, true);
        if (views.size() != ids.size()) throw new SalesNotFoundException("요청한 SKU를 모두 찾을 수 없습니다.");
        return views;
    }

    /** Product 존재·소유·미삭제 확인과 같은 트랜잭션으로 호출하는 내부 생성 포트다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SkuView create(Long productId, long price, int initialAvailable, String productName) {
        requireId(productId);
        if (price <= 0 || initialAvailable < 0)
            throw new IllegalArgumentException("가격·초기 재고가 올바르지 않습니다.");
        if (productName == null || productName.length() > 255)
            throw new IllegalArgumentException("상품명이 올바르지 않습니다.");
        long id = repository.create(productId, price, initialAvailable, productName);
        events.registered(id);
        return required(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SkuView changePrice(Long skuId, long price) {
        if (price <= 0) throw new IllegalArgumentException("가격은 KRW 1원 이상이어야 합니다.");
        watch(skuId);
        repository.changePrice(skuId, price);
        return required(skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SkuView changeSaleStatus(Long skuId, SkuSaleStatus status) {
        if (status == null || status == SkuSaleStatus.READY)
            throw new IllegalArgumentException("판매자는 ON_SALE 또는 PRIVATE만 지정할 수 있습니다.");
        watch(skuId);
        repository.changeStatus(skuId, status);
        return required(skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SkuView adjustAvailable(Long skuId, int delta) {
        watch(skuId);
        if (delta != 0 && repository.adjustAvailable(skuId, delta) != 1)
            throw new InsufficientStockException("재고 증감 범위를 벗어났습니다: skuId=" + skuId);
        return required(skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long skuId, int quantity) {
        requireQuantity(quantity);
        watch(skuId);
        if (repository.reserve(skuId, quantity) != 1)
            throw new InsufficientStockException("판매 상태 또는 예약 가능 재고가 부족합니다: skuId=" + skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Long skuId, int quantity) {
        requireQuantity(quantity);
        watch(skuId);
        if (repository.consume(skuId, quantity) != 1)
            throw new IllegalStateException("소진할 예약 재고가 부족합니다: skuId=" + skuId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Long skuId, int quantity) {
        requireQuantity(quantity);
        watch(skuId);
        if (repository.release(skuId, quantity) != 1)
            throw new IllegalStateException("해제할 예약 재고가 부족하거나 복구 범위를 벗어났습니다: skuId=" + skuId);
    }

    /** Order가 판매된 항목에 환불 성공을 처음 적용할 때 호출한다. 만료 후 보상 환불은 제외한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restock(Long skuId, int quantity) {
        requireQuantity(quantity);
        watch(skuId);
        if (repository.adjustAvailable(skuId, quantity) != 1)
            throw new IllegalStateException("환불 재고 복구 범위를 벗어났습니다: skuId=" + skuId);
    }

    private void watch(Long skuId) {
        requireId(skuId);
        events.watch(skuId);
    }

    private SkuView required(Long id) {
        return repository.findBySkuIds(List.of(id), false).stream()
                .findFirst()
                .orElseThrow(() -> new SalesNotFoundException("SKU를 찾을 수 없습니다: skuId=" + id));
    }

    private static List<Long> sortedIds(Collection<Long> ids) {
        Objects.requireNonNull(ids, "skuIds는 필수입니다.");
        ids.forEach(SkuService::requireId);
        return ids.stream().distinct().sorted().toList();
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("ID는 양수여야 합니다.");
    }

    private static void requireQuantity(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("수량은 양수여야 합니다.");
    }
}
