package com.shoppinglive.shopping.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.client.commerce.SalesDisplayStatus;
import com.shoppinglive.shopping.product.dto.PublicProductItemResponse;
import com.shoppinglive.shopping.product.dto.PublicProductListResponse;
import com.shoppinglive.shopping.product.dto.SellerProductPage;
import com.shoppinglive.shopping.product.dto.SellerProductSummary;
import com.shoppinglive.shopping.storage.ImageUrlResolver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가격·상태의 전체 목록 조건은 기존 이벤트 읽기 모델에서 함께 적용한다. 상세·주문 확정 경로는 바꾸지 않는다. */
@Service
public class CatalogQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ImageUrlResolver images;

    public CatalogQueryService(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ImageUrlResolver images) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public PublicProductListResponse list(
            String query, SalesDisplayStatus status, ProductSort sort, String cursor, int size) {
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if ((query != null && query.length() > 100)
                || (status != null && !status.isPubliclyVisible())) {
            throw invalid("검색어는 100자 이하이며 공개 상태는 ON_SALE 또는 SOLD_OUT 이어야 합니다.");
        }
        ProductSort ordering = sort == null ? ProductSort.LATEST : sort;
        String fingerprint = keyword + "\n" + status + "\n" + ordering;
        Map<String, Object> params = new HashMap<>();
        params.put(
                "keyword",
                "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        params.put("size", size + 1);
        String where =
                " WHERE s.status IN ('ON_SALE', 'SOLD_OUT') AND LOWER(p.name) LIKE :keyword ESCAPE '!'";
        if (status != null) {
            where += " AND s.status = :status";
            params.put("status", status.name());
        }
        if (cursor != null && !cursor.isBlank()) {
            Position after = decode(cursor);
            if (!fingerprint.equals(after.fingerprint())
                    || after.id() <= 0
                    || after.price() < 0
                    || after.createdAt() == null) {
                throw invalid("cursor 는 같은 검색·상태·정렬 조건에서만 사용할 수 있습니다.");
            }
            params.put("id", after.id());
            params.put("createdAt", java.sql.Timestamp.from(after.createdAt()));
            params.put("price", after.price());
            where +=
                    switch (ordering) {
                        case LATEST ->
                                " AND (p.created_at < :createdAt OR (p.created_at = :createdAt AND p.id < :id))";
                        case PRICE_ASC ->
                                " AND (s.price > :price OR (s.price = :price AND p.id > :id))";
                        case PRICE_DESC ->
                                " AND (s.price < :price OR (s.price = :price AND p.id < :id))";
                    };
        }
        String order =
                switch (ordering) {
                    case LATEST -> "p.created_at DESC, p.id DESC";
                    case PRICE_ASC -> "s.price ASC, p.id ASC";
                    case PRICE_DESC -> "s.price DESC, p.id DESC";
                };
        List<CatalogRow> rows =
                jdbc.query(
                        "SELECT p.id, p.name, p.main_image_id, p.created_at, s.price, s.status, s.available FROM product p JOIN product_sales s ON s.product_id = p.id"
                                + where
                                + " ORDER BY "
                                + order
                                + " LIMIT :size",
                        params,
                        (rs, n) ->
                                new CatalogRow(
                                        rs.getLong("id"),
                                        rs.getString("name"),
                                        rs.getLong("main_image_id"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getLong("price"),
                                        SalesDisplayStatus.valueOf(rs.getString("status")),
                                        rs.getInt("available")));
        boolean hasNext = rows.size() > size;
        List<CatalogRow> page = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (hasNext) {
            CatalogRow last = page.getLast();
            next = encode(new Position(fingerprint, last.createdAt(), last.id(), last.price()));
        }
        return new PublicProductListResponse(
                page.stream()
                        .map(
                                row ->
                                        new PublicProductItemResponse(
                                                row.id(),
                                                row.name(),
                                                images.urlOf(row.imageId()),
                                                row.price(),
                                                row.status(),
                                                row.status().isPurchasable()
                                                        && row.available() > 0))
                        .toList(),
                next,
                hasNext);
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
                        "SELECT COUNT(*) FROM product p LEFT JOIN product_sales s ON s.product_id = p.id"
                                + where,
                        params,
                        Long.class);
        List<SellerProductSummary> rows =
                jdbc.query(
                        "SELECT p.id, p.name, p.main_image_id, p.created_at, s.price, s.available FROM product p LEFT JOIN product_sales s ON s.product_id = p.id"
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

    private Position decode(String cursor) {
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            return Objects.requireNonNull(
                    mapper.readValue(Base64.getUrlDecoder().decode(cursor), Position.class));
        } catch (Exception e) {
            throw invalid("cursor 값이 올바르지 않습니다.");
        }
    }

    private String encode(Position position) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mapper.writeValueAsString(position).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("목록 커서를 만들지 못했습니다.", e);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private record Position(String fingerprint, Instant createdAt, long id, long price) {}

    private record CatalogRow(
            long id,
            String name,
            long imageId,
            Instant createdAt,
            long price,
            SalesDisplayStatus status,
            int available) {}
}
