package com.shoppinglive.shopping.product.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.client.commerce.SalesDisplayStatus;
import com.shoppinglive.shopping.client.commerce.SalesInfo;
import com.shoppinglive.shopping.client.commerce.SalesInfoClient;
import com.shoppinglive.shopping.client.commerce.SalesInfoUnavailableException;
import com.shoppinglive.shopping.product.dto.SellerProductDetail;
import com.shoppinglive.shopping.product.dto.SellerProductPage;
import com.shoppinglive.shopping.product.dto.SellerProductSummary;
import com.shoppinglive.shopping.product.entity.Product;
import com.shoppinglive.shopping.product.repository.ProductRepository;
import com.shoppinglive.shopping.storage.ImageUrlResolver;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 판매자 상품 목록·상세. 본인 상품만 돌려주고, Shopping 기본정보에 Commerce 판매정보를 붙인다.
 *
 * <p>판매자는 판매정보를 못 불러와도 상품 기본정보는 봐야 하므로, 조회 실패를 503 으로 돌리지 않고 판매 상태만 {@code UNKNOWN} 으로 표시한다.
 * UNKNOWN 을 품절로 보이게 하지 않도록 가격·재고도 비운다. 원격 호출이 DB 트랜잭션을 붙잡지 않도록 메서드에 트랜잭션을 걸지 않는다.
 */
@Service
public class SellerProductQueryService {

    private static final Logger log = LoggerFactory.getLogger(SellerProductQueryService.class);
    private static final Sort LATEST_FIRST =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductRepository productRepository;
    private final SalesInfoClient salesInfoClient;
    private final ImageUrlResolver imageUrlResolver;

    public SellerProductQueryService(
            ProductRepository productRepository,
            SalesInfoClient salesInfoClient,
            ImageUrlResolver imageUrlResolver) {
        this.productRepository = productRepository;
        this.salesInfoClient = salesInfoClient;
        this.imageUrlResolver = imageUrlResolver;
    }

    /**
     * 본인 상품의 최신 등록순 페이지. 소유자가 없는 기존 상품은 나오지 않는다. 판매정보는 페이지 상품을 모아 한 번에 조회한다 (N+1 방지).
     *
     * @throws BusinessException INVALID_REQUEST — page × size 가 int 범위를 넘으면 offset 계산이 넘친다
     */
    public SellerProductPage list(String sellerId, int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "page × size는 2147483647 이하여야 합니다.");
        }
        Page<Product> products =
                productRepository.findBySellerId(
                        sellerId, PageRequest.of(page, size, LATEST_FIRST));
        List<Long> ids = products.getContent().stream().map(Product::getId).toList();
        Map<Long, SalesInfo> sales = findSalesOrNull(ids);
        List<SellerProductSummary> items =
                products.getContent().stream()
                        .map(
                                product -> {
                                    SalesView view =
                                            sales == null
                                                    ? SalesView.UNKNOWN
                                                    : SalesView.of(sales.get(product.getId()));
                                    return new SellerProductSummary(
                                            product.getId(),
                                            product.getName(),
                                            mainImageUrl(product),
                                            product.getCreatedAt(),
                                            view.status(),
                                            view.price(),
                                            view.available());
                                })
                        .toList();
        return new SellerProductPage(
                items, page, size, products.getTotalElements(), products.getTotalPages());
    }

    /**
     * @throws BusinessException NOT_FOUND — 상품이 없거나 본인 상품이 아니다. 다른 판매자 상품의 존재 여부를 드러내지 않으려고 둘을 구분하지
     *     않는다
     */
    public SellerProductDetail get(Long productId, String sellerId) {
        Product product =
                productRepository
                        .findById(productId)
                        .filter(found -> sellerId.equals(found.getSellerId()))
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND,
                                                "상품을 찾을 수 없습니다: " + productId));
        SalesView view;
        try {
            view = SalesView.of(salesInfoClient.findByProductId(productId).orElse(null));
        } catch (SalesInfoUnavailableException e) {
            log.warn(
                    "seller product detail without sales info: productId={} cause={}",
                    productId,
                    e.getMessage());
            view = SalesView.UNKNOWN;
        }
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
                view.available());
    }

    /** 조회 실패면 {@code null} — 호출 측이 모든 항목을 UNKNOWN 으로 표시한다. */
    private Map<Long, SalesInfo> findSalesOrNull(List<Long> productIds) {
        try {
            return salesInfoClient.findByProductIds(productIds);
        } catch (SalesInfoUnavailableException e) {
            log.warn(
                    "seller product list without sales info: ids={} cause={}",
                    productIds.size(),
                    e.getMessage());
            return null;
        }
    }

    private String mainImageUrl(Product product) {
        return imageUrlResolver.urlOf(product.getMainImageId());
    }

    /** 표시 상태와 가격·재고를 한 규칙으로 묶는다. 판매정보가 없거나 모르면 가격·재고는 null. */
    private record SalesView(SalesDisplayStatus status, Long price, Integer available) {

        static final SalesView UNKNOWN = new SalesView(SalesDisplayStatus.unknown(), null, null);

        static SalesView of(SalesInfo info) {
            return info == null
                    ? new SalesView(SalesDisplayStatus.from(null), null, null)
                    : new SalesView(SalesDisplayStatus.from(info), info.price(), info.available());
        }
    }
}
