package com.shoppinglive.shopping.product.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 관리용 상품 목록·상세. Shopping 기본정보에 Commerce 판매정보를 붙인다.
 *
 * <p>관리자는 판매정보를 못 불러와도 상품 기본정보는 봐야 하므로, 조회 실패를 503 으로 돌리지 않고 판매 상태만
 * {@code UNKNOWN} 으로 표시한다. UNKNOWN 을 품절로 보이게 하지 않도록 가격·재고도 비운다.
 * 원격 호출이 DB 트랜잭션을 붙잡지 않도록 메서드에 트랜잭션을 걸지 않는다.
 */
@Service
public class AdminProductQueryService {

    private static final Logger log = LoggerFactory.getLogger(AdminProductQueryService.class);
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductRepository productRepository;
    private final SalesInfoClient salesInfoClient;
    private final ImageUrlResolver imageUrlResolver;

    public AdminProductQueryService(ProductRepository productRepository, SalesInfoClient salesInfoClient,
            ImageUrlResolver imageUrlResolver) {
        this.productRepository = productRepository;
        this.salesInfoClient = salesInfoClient;
        this.imageUrlResolver = imageUrlResolver;
    }

    /** 최신 등록순 페이지. 판매정보는 페이지 상품을 모아 한 번에 조회한다 (N+1 방지). */
    public AdminProductPage list(int page, int size) {
        Page<Product> products = productRepository.findAll(PageRequest.of(page, size, LATEST_FIRST));
        List<Long> ids = products.getContent().stream().map(Product::getId).toList();
        Map<Long, SalesInfo> sales = findSalesOrNull(ids);
        List<AdminProductSummary> items = products.getContent().stream().map(product -> {
            SalesView view = sales == null ? SalesView.UNKNOWN : SalesView.of(sales.get(product.getId()));
            return new AdminProductSummary(product.getId(), product.getName(), mainImageUrl(product),
                    product.getCreatedAt(), view.status(), view.price(), view.available());
        }).toList();
        return new AdminProductPage(items, page, size, products.getTotalElements(), products.getTotalPages());
    }

    /** @throws BusinessException NOT_FOUND */
    public AdminProductDetail get(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + productId));
        SalesView view;
        try {
            view = SalesView.of(salesInfoClient.findByProductId(productId).orElse(null));
        } catch (SalesInfoUnavailableException e) {
            log.warn("admin product detail without sales info: productId={} cause={}", productId, e.getMessage());
            view = SalesView.UNKNOWN;
        }
        return new AdminProductDetail(product.getId(), product.getName(), product.getDescription(),
                product.getMainImageId(), mainImageUrl(product), product.getVersion(), product.getCreatedAt(),
                product.getUpdatedAt(), view.status(), view.price(), view.available());
    }

    /** 조회 실패면 {@code null} — 호출 측이 모든 항목을 UNKNOWN 으로 표시한다. */
    private Map<Long, SalesInfo> findSalesOrNull(List<Long> productIds) {
        try {
            return salesInfoClient.findByProductIds(productIds);
        } catch (SalesInfoUnavailableException e) {
            log.warn("admin product list without sales info: ids={} cause={}", productIds.size(), e.getMessage());
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
