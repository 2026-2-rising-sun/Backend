package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.SalesDisplayStatus;
import com.shoppinglive.commercecore.product.dto.SellerProductDetail;
import com.shoppinglive.commercecore.product.dto.SellerProductPage;
import com.shoppinglive.commercecore.product.dto.SellerProductSummary;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.sales.dto.SalesLookup;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.storage.ImageUrlResolver;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 상품과 판매·재고 원본을 같은 DB에서 읽는다. */
@Service
public class SellerProductQueryService {

    private static final Sort LATEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductRepository productRepository;
    private final SalesJpaRepository salesRepository;
    private final ImageUrlResolver imageUrlResolver;

    public SellerProductQueryService(
            ProductRepository productRepository,
            SalesJpaRepository salesRepository,
            ImageUrlResolver imageUrlResolver) {
        this.productRepository = productRepository;
        this.salesRepository = salesRepository;
        this.imageUrlResolver = imageUrlResolver;
    }

    /**
     * 본인 상품의 최신 등록순 페이지. 소유자가 없는 기존 상품은 나오지 않는다. 판매정보는 페이지 상품을 모아 한 번에 조회한다 (N+1 방지).
     *
     * @throws BusinessException INVALID_REQUEST — page × size 가 int 범위를 넘으면 offset 계산이 넘친다
     */
    @Transactional(readOnly = true)
    public SellerProductPage list(String sellerId, int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "page × size는 2147483647 이하여야 합니다.");
        }
        Page<Product> products =
                productRepository.findBySellerIdAndDeletedAtIsNull(
                        sellerId, PageRequest.of(page, size, LATEST_FIRST));
        List<Long> ids = products.getContent().stream().map(Product::getId).toList();
        Map<Long, SalesLookup> sales = findSales(ids);
        List<SellerProductSummary> items =
                products.getContent().stream()
                        .map(
                                product -> {
                                    SalesView view = SalesView.of(sales.get(product.getId()));
                                    return new SellerProductSummary(
                                            product.getId(),
                                            product.getName(),
                                            mainImageUrl(product),
                                            product.getCreatedAt(),
                                            view.status(),
                                            view.price(),
                                            view.available(),
                                            product.isSaleEnabled());
                                })
                        .toList();
        return new SellerProductPage(
                items, page, size, products.getTotalElements(), products.getTotalPages());
    }

    /**
     * @throws BusinessException NOT_FOUND — 상품이 없거나 본인 상품이 아니다. 다른 판매자 상품의 존재 여부를 드러내지 않으려고 둘을 구분하지
     *     않는다
     */
    @Transactional(readOnly = true)
    public SellerProductDetail get(Long productId, String sellerId) {
        Product product =
                productRepository
                        .findById(productId)
                        .filter(found -> sellerId.equals(found.getSellerId()))
                        .filter(found -> !found.isDeleted())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND,
                                                "상품을 찾을 수 없습니다: " + productId));
        SalesView view = SalesView.of(findSales(List.of(productId)).get(productId));
        return new SellerProductDetail(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getMainImageId(),
                mainImageUrl(product),
                product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                view.status(),
                view.price(),
                view.available(),
                product.getCategory(),
                product.isSaleEnabled());
    }

    private Map<Long, SalesLookup> findSales(List<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return salesRepository.findLookupsByProductIds(productIds).stream()
                .collect(Collectors.toMap(SalesLookup::productId, Function.identity()));
    }

    private String mainImageUrl(Product product) {
        return imageUrlResolver.urlOf(product.getMainImageId());
    }

    /** 표시 상태와 가격·재고를 한 규칙으로 묶는다. 판매정보가 없으면 가격·재고는 null. */
    private record SalesView(SalesDisplayStatus status, Long price, Integer available) {

        static SalesView of(SalesLookup info) {
            return info == null
                    ? new SalesView(SalesDisplayStatus.from(null), null, null)
                    : new SalesView(SalesDisplayStatus.from(info), info.price(), info.available());
        }
    }
}
