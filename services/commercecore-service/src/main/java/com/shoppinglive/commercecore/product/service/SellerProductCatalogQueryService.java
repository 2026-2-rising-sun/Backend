package com.shoppinglive.commercecore.product.service;

import com.shoppinglive.commercecore.product.dto.*;
import com.shoppinglive.commercecore.storage.ImageUrlResolver;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 상태 필터를 상품·판매·재고 원본의 로컬 조인으로 적용한다. */
@Service
public class SellerProductCatalogQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ImageUrlResolver images;

    public SellerProductCatalogQueryService(
            NamedParameterJdbcTemplate jdbc, ImageUrlResolver images) {
        this.jdbc = jdbc;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public SellerProductPage sellerList(
            String sellerId, SalesDisplayStatus status, int page, int size) {
        if (status == SalesDisplayStatus.UNKNOWN || (long) page * size > Integer.MAX_VALUE) {
            throw invalid("판매 상태 또는 페이지 범위가 올바르지 않습니다.");
        }
        String where =
                " WHERE p.seller_id = :sellerId AND "
                        + (status == SalesDisplayStatus.NOT_REGISTERED
                                ? "s.product_id IS NULL"
                                : "s.status = :status");
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
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM product p LEFT JOIN sales_info s ON s.product_id = p.id LEFT JOIN sales_stock st ON st.sales_info_id = s.id"
                                + where,
                        params,
                        Long.class);
        List<SellerProductSummary> rows =
                jdbc.query(
                        "SELECT p.id, p.name, p.main_image_id, p.created_at, s.price, st.available FROM product p LEFT JOIN sales_info s ON s.product_id = p.id LEFT JOIN sales_stock st ON st.sales_info_id = s.id"
                                + where
                                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :size OFFSET :offset",
                        params,
                        (rs, n) ->
                                new SellerProductSummary(
                                        rs.getLong("id"),
                                        rs.getString("name"),
                                        images.urlOf(rs.getLong("main_image_id")),
                                        rs.getTimestamp("created_at").toInstant(),
                                        status,
                                        rs.getObject("price", Long.class),
                                        rs.getObject("available", Integer.class)));
        long count = Objects.requireNonNull(total);
        return new SellerProductPage(rows, page, size, count, (int) ((count + size - 1) / size));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
