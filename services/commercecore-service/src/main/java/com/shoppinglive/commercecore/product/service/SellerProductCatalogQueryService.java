package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.sales.dto.SalesLookup;
import com.shoppinglive.commercecore.sku.service.SkuSalesService;
import com.shoppinglive.commercecore.storage.ImageUrlResolver;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 상태 필터를 상품·판매·재고 원본의 로컬 조인으로 적용한다. */
@Service
public class SellerProductCatalogQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ImageUrlResolver images;
    private final SkuSalesService singleSales;

    public SellerProductCatalogQueryService(
            NamedParameterJdbcTemplate jdbc, ImageUrlResolver images, SkuSalesService singleSales) {
        this.jdbc = jdbc;
        this.images = images;
        this.singleSales = singleSales;
    }

    @Transactional(readOnly = true)
    public SellerProductPage sellerList(
            String sellerId, SalesDisplayStatus status, int page, int size) {
        if (status == SalesDisplayStatus.UNKNOWN || (long) page * size > Integer.MAX_VALUE) {
            throw invalid("판매 상태 또는 페이지 범위가 올바르지 않습니다.");
        }
        String display =
                "CASE WHEN s.sale_status='ON_SALE' AND s.available=0 THEN 'SOLD_OUT' ELSE s.sale_status END";
        String where =
                " WHERE p.seller_id=:sellerId AND p.deleted_at IS NULL AND "
                        + (status == SalesDisplayStatus.NOT_REGISTERED
                                ? "NOT EXISTS (SELECT 1 FROM sku s WHERE s.product_id=p.id)"
                                : "EXISTS (SELECT 1 FROM sku s WHERE s.product_id=p.id AND "
                                        + display
                                        + "=:status)");
        Map<String, Object> params =
                Map.of(
                        "sellerId",
                        sellerId,
                        "status",
                        status.name(),
                        "size",
                        size,
                        "offset",
                        (long) page * size);
        Long total =
                jdbc.queryForObject("SELECT COUNT(*) FROM product p" + where, params, Long.class);
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT p.id FROM product p"
                                + where
                                + " ORDER BY p.created_at DESC,p.id DESC LIMIT :size OFFSET :offset",
                        params,
                        Long.class);
        Map<Long, SalesLookup> sales =
                singleSales.findSingleByProductIds(ids).stream()
                        .collect(Collectors.toMap(SalesLookup::productId, Function.identity()));
        List<SellerProductSummary> rows =
                ids.isEmpty()
                        ? List.of()
                        : jdbc.query(
                                "SELECT id,name,main_image_id,created_at,sale_enabled FROM product WHERE id IN (:ids) ORDER BY created_at DESC,id DESC",
                                Map.of("ids", ids),
                                (rs, n) -> {
                                    var sale = sales.get(rs.getLong("id"));
                                    return new SellerProductSummary(
                                            rs.getLong("id"),
                                            rs.getString("name"),
                                            images.urlOf(rs.getLong("main_image_id")),
                                            rs.getTimestamp("created_at").toInstant(),
                                            SalesDisplayStatus.from(sale),
                                            sale == null ? null : sale.price(),
                                            sale == null ? null : sale.available(),
                                            rs.getBoolean("sale_enabled"));
                                });
        long count = Objects.requireNonNull(total);
        return new SellerProductPage(rows, page, size, count, (int) ((count + size - 1) / size));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
