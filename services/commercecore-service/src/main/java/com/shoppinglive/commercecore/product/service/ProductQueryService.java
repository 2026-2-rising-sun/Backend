package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.storage.ImageUrlResolver;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 서비스 간 상품 조회. */
@Service
public class ProductQueryService implements ProductLookup {

    private final ProductRepository productRepository;
    private final ImageUrlResolver imageUrlResolver;

    public ProductQueryService(
            ProductRepository productRepository, ImageUrlResolver imageUrlResolver) {
        this.productRepository = productRepository;
        this.imageUrlResolver = imageUrlResolver;
    }

    public ProductSnapshot getSnapshot(Long productId) {
        return productRepository
                .findById(productId)
                .map(this::toSnapshot)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + productId));
    }

    /** 없는 id 는 빠지고, 결과는 요청 순서를 따른다. */
    public List<ProductSnapshot> findSnapshots(Collection<Long> productIds) {
        Map<Long, Product> byId =
                productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, Function.identity()));
        return productIds.stream()
                .distinct()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(this::toSnapshot)
                .toList();
    }

    @Override
    public java.util.Optional<ProductSnapshot> findProduct(Long productId) {
        return productRepository.findById(productId).map(this::toSnapshot);
    }

    @Override
    public List<ProductSnapshot> findProducts(Collection<Long> productIds) {
        return findSnapshots(productIds);
    }

    @Override
    public boolean exists(Long productId) {
        return productRepository.existsById(productId);
    }

    private ProductSnapshot toSnapshot(Product product) {
        return new ProductSnapshot(
                product.getId(),
                product.getName(),
                imageUrlResolver.urlOf(product.getMainImageId()),
                product.getSellerId());
    }
}
