package com.shoppinglive.shopping.product.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 서비스 간 상품 조회. */
@Service
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final ImageUrlResolver imageUrlResolver;

    public ProductQueryService(ProductRepository productRepository, ImageUrlResolver imageUrlResolver) {
        this.productRepository = productRepository;
        this.imageUrlResolver = imageUrlResolver;
    }

    /** @throws BusinessException NOT_FOUND — 호출 측(Commerce ShoppingClient)은 404 를 "상품 없음"으로 해석한다 */
    public ProductSnapshot getSnapshot(Long productId) {
        return productRepository.findById(productId)
                .map(this::toSnapshot)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + productId));
    }

    /** 없는 id 는 빠지고, 결과는 요청 순서를 따른다. */
    public List<ProductSnapshot> findSnapshots(Collection<Long> productIds) {
        Map<Long, Product> byId = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return productIds.stream().map(byId::get).filter(Objects::nonNull).map(this::toSnapshot).toList();
    }

    private ProductSnapshot toSnapshot(Product product) {
        return new ProductSnapshot(product.getId(), product.getName(), imageUrlResolver.urlOf(product.getMainImageId()));
    }
}
