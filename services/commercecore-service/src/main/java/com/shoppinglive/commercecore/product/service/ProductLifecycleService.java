package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.event.ProductInfoEventPublisher;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Instant;
import java.util.Objects;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductLifecycleService {
    private final ProductRepository products;
    private final ProductInfoEventPublisher events;

    public ProductLifecycleService(ProductRepository products, ProductInfoEventPublisher events) {
        this.products = products;
        this.events = events;
    }

    @Transactional
    public Product changeSaleState(
            Long productId, boolean saleEnabled, Long version, String sellerId) {
        Product product = ownedProduct(productId, version, sellerId);
        if (product.isDeleted()) throw notFound(productId);
        requireVersion(product, version);
        if (product.isSaleEnabled() == saleEnabled) return product;
        product.changeSaleEnabled(saleEnabled);
        flushAndPublish(product);
        return product;
    }

    @Transactional
    public void delete(Long productId, Long version, String sellerId) {
        Product product = ownedProduct(productId, version, sellerId);
        // 첫 삭제의 응답을 받지 못한 재전송은 같은 판매자에게 추가 전이 없이 성공한다.
        if (product.isDeleted()) return;
        requireVersion(product, version);
        product.softDelete(Instant.now());
        flushAndPublish(product);
    }

    private Product ownedProduct(Long id, Long version, String sellerId) {
        if (id == null || id <= 0 || version == null || version < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "상품 ID와 version이 올바르지 않습니다.");
        }
        Product product = products.findById(id).orElseThrow(() -> notFound(id));
        if (sellerId == null || !sellerId.equals(product.getSellerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 상품만 제어할 수 있습니다.");
        }
        return product;
    }

    private void requireVersion(Product product, Long version) {
        if (!Objects.equals(product.getVersion(), version)) {
            throw new BusinessException(ErrorCode.CONFLICT, ProductUpdateService.CONFLICT_MESSAGE);
        }
    }

    private void flushAndPublish(Product product) {
        try {
            products.flush();
        } catch (ObjectOptimisticLockingFailureException conflict) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, ProductUpdateService.CONFLICT_MESSAGE, conflict);
        }
        events.publish(product);
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + id);
    }
}
