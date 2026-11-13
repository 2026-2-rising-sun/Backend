package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.ProductTradeSnapshot;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.storage.ImageUrlResolver;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 신규 거래에만 사용한다. 완료·해제·환불 복구는 상품 판매 상태를 다시 검사하지 않는다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ProductTradingService {
    private final ProductRepository products;
    private final ImageUrlResolver images;
    private final EntityManager entityManager;

    public ProductTradingService(
            ProductRepository products, ImageUrlResolver images, EntityManager entityManager) {
        this.products = products;
        this.images = images;
        this.entityManager = entityManager;
    }

    /** 모든 상품 잠금을 먼저 얻은 뒤 호출자가 모든 SKU를 정렬해 잠근다. */
    public List<ProductTradeSnapshot> lockForNewOrder(Collection<Long> productIds) {
        if (productIds == null
                || productIds.isEmpty()
                || productIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 ID가 올바르지 않습니다.");
        }
        return productIds.stream()
                .distinct()
                .sorted()
                .map(
                        id -> {
                            Product product = lock(id);
                            if (!product.isSaleEnabled()) {
                                throw new BusinessException(
                                        ErrorCode.CONFLICT, "판매가 중지된 상품입니다: " + id);
                            }
                            return snapshot(product);
                        })
                .toList();
    }

    /** 판매 준비 중인 상품에도 SKU를 추가할 수 있다. */
    public ProductTradeSnapshot lockForSkuCreation(Long productId, String sellerId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 ID가 올바르지 않습니다.");
        }
        Product product = lock(productId);
        if (sellerId == null || !sellerId.equals(product.getSellerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 상품에만 SKU를 등록할 수 있습니다.");
        }
        return snapshot(product);
    }

    private Product lock(Long id) {
        Product product = products.findById(id).orElseThrow(() -> notFound(id));
        // 잠금 쿼리부터 실행하면 캐시된 옛 version과 충돌할 수 있어 refresh로 잠금·최신화를 함께 한다.
        entityManager.refresh(product, LockModeType.PESSIMISTIC_READ);
        if (product.isDeleted()) throw notFound(id);
        return product;
    }

    private ProductTradeSnapshot snapshot(Product p) {
        return new ProductTradeSnapshot(
                p.getId(),
                p.getName(),
                p.getSellerId(),
                images.urlOf(p.getMainImageId()),
                p.getVersion());
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + id);
    }
}
