package com.shoppinglive.catalog.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.catalog.client.commercecore.SalesDisplayStatus;
import com.shoppinglive.catalog.product.dto.PublicProductItemResponse;
import com.shoppinglive.catalog.product.dto.PublicProductListResponse;
import com.shoppinglive.catalog.product.entity.ProductCategory;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
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

    public CatalogQueryService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PublicProductListResponse list(
            String query,
            SalesDisplayStatus status,
            ProductCategory category,
            ProductSort sort,
            String cursor,
            int size) {
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if ((query != null && query.length() > 100)
                || (status != null && !status.isPubliclyVisible())) {
            throw invalid("검색어는 100자 이하이며 공개 상태는 ON_SALE 또는 SOLD_OUT 이어야 합니다.");
        }
        ProductSort ordering = sort == null ? ProductSort.LATEST : sort;
        // 카테고리가 없을 때는 기존 커서 형식을 그대로 받는다.
        String fingerprint =
                keyword
                        + "\n"
                        + status
                        + "\n"
                        + ordering
                        + (category == null ? "" : "\n" + category);
        Map<String, Object> params = new HashMap<>();
        params.put(
                "keyword",
                "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        params.put("size", size + 1);
        String where =
                " WHERE p.deleted_at IS NULL AND s.status IN ('ON_SALE', 'SOLD_OUT') AND LOWER(p.name) LIKE :keyword ESCAPE '!'";
        if (status != null) {
            where += " AND s.status = :status";
            params.put("status", status.name());
        }
        if (category != null) {
            where += " AND p.category = :category";
            params.put("category", category.name());
        }
        if (cursor != null && !cursor.isBlank()) {
            Position after = decode(cursor);
            if (!fingerprint.equals(after.fingerprint())
                    || after.id() <= 0
                    || after.price() < 0
                    || after.createdAt() == null) {
                throw invalid("cursor 는 같은 검색·상태·카테고리·정렬 조건에서만 사용할 수 있습니다.");
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
                        "SELECT p.id, p.name, p.main_image_url, p.category, p.created_at, p.sale_enabled, s.price, s.status, s.available FROM product p JOIN product_sales s ON s.product_id = p.id"
                                + where
                                + " ORDER BY "
                                + order
                                + " LIMIT :size",
                        params,
                        (rs, n) ->
                                new CatalogRow(
                                        rs.getLong("id"),
                                        rs.getString("name"),
                                        rs.getString("main_image_url"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getLong("price"),
                                        SalesDisplayStatus.valueOf(rs.getString("status")),
                                        rs.getInt("available"),
                                        rs.getString("category"),
                                        rs.getBoolean("sale_enabled")));
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
                                                row.imageUrl(),
                                                row.price(),
                                                row.available(),
                                                row.status(),
                                                row.saleEnabled()
                                                        && row.status().isPurchasable()
                                                        && row.available() > 0,
                                                row.category() == null
                                                        ? null
                                                        : ProductCategory.valueOf(row.category())))
                        .toList(),
                next,
                hasNext);
    }

    private Position decode(String cursor) {
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            Position position =
                    Objects.requireNonNull(
                            mapper.readValue(
                                    Base64.getUrlDecoder().decode(cursor), Position.class));
            // JDBC 시각 변환의 범위 초과·잘림이 입력 오류 대신 쿼리 오류가 되지 않게 막는다.
            if (position.createdAt() == null
                    || position.createdAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || position.createdAt()
                            .isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))) {
                throw new IllegalArgumentException();
            }
            return position;
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
            String imageUrl,
            Instant createdAt,
            long price,
            SalesDisplayStatus status,
            int available,
            String category,
            boolean saleEnabled) {}
}
