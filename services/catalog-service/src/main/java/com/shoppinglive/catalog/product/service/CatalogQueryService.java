package com.shoppinglive.catalog.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.catalog.client.commercecore.SalesDisplayStatus;
import com.shoppinglive.catalog.product.dto.PublicProductItemResponse;
import com.shoppinglive.catalog.product.dto.PublicProductListResponse;
import com.shoppinglive.catalog.product.entity.ProductCategory;
import com.shoppinglive.catalog.search.ProductSearch;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 목록. ES 가 조건에 맞는 상품 id 를 정렬된 순서로 찾고, 가격, 재고, 판매 상태는 Catalog DB 에서 지금 값을 붙인다. 상세, 주문 확정 경로는 바꾸지
 * 않는다.
 *
 * <p>SKU 가 여러 개인 상품은 한 줄로 보여 준다. 판매 중 SKU 가 하나라도 있으면 판매 중이고 가격은 판매 중 SKU 최저가, 재고는 판매 중 SKU 재고의 합이다.
 * 모두 품절이면 품절 SKU 의 최저가와 재고 합이다. 가격 범위는 ES 에 색인한 같은 대표 가격으로 거른다. ES 가 아직 반영하지 못해 지금은 공개 대상이 아닌 상품은
 * 응답에서 뺀다.
 */
@Service
public class CatalogQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProductSearch search;

    public CatalogQueryService(
            NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ProductSearch search) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.search = search;
    }

    @Transactional(readOnly = true)
    public PublicProductListResponse list(
            String query,
            SalesDisplayStatus status,
            ProductCategory category,
            Long minPrice,
            Long maxPrice,
            ProductSort sort,
            String cursor,
            int size) {
        String keyword = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if ((query != null && query.length() > 100)
                || (status != null && !status.isPubliclyVisible())) {
            throw invalid("검색어는 100자 이하이며 공개 상태는 ON_SALE 또는 SOLD_OUT 이어야 합니다.");
        }
        if ((minPrice != null && minPrice < 0)
                || (maxPrice != null && maxPrice < 0)
                || (minPrice != null && maxPrice != null && minPrice > maxPrice)) {
            throw invalid("minPrice, maxPrice 는 0 이상이며 minPrice 는 maxPrice 보다 클 수 없습니다.");
        }
        ProductSort ordering = sort == null ? ProductSort.LATEST : sort;
        // 카테고리, 가격 범위가 없을 때는 그 조건 도입 전 커서와 같은 조건 문자열을 쓴다.
        String fingerprint =
                keyword
                        + "\n"
                        + status
                        + "\n"
                        + ordering
                        + (category == null ? "" : "\n" + category)
                        + (minPrice == null && maxPrice == null
                                ? ""
                                : "\nprice:" + minPrice + "~" + maxPrice);
        List<Long> after = null;
        if (cursor != null && !cursor.isBlank()) {
            Position position = decode(cursor);
            if (!fingerprint.equals(position.fingerprint())) {
                throw invalid("cursor 는 같은 검색·상태·카테고리·가격 범위·정렬 조건에서만 사용할 수 있습니다.");
            }
            after = position.after();
        }
        ProductSearch.Page found =
                search.find(keyword, status, category, minPrice, maxPrice, ordering, after, size);
        Map<Long, PublicProductItemResponse> rows = load(found.productIds());
        String next = found.hasNext() ? encode(new Position(fingerprint, found.lastSort())) : null;
        return new PublicProductListResponse(
                found.productIds().stream().map(rows::get).filter(Objects::nonNull).toList(),
                next,
                found.hasNext());
    }

    private Map<Long, PublicProductItemResponse> load(List<Long> ids) {
        Map<Long, PublicProductItemResponse> rows = new HashMap<>();
        if (ids.isEmpty()) return rows;
        jdbc.query(
                """
                SELECT p.id, p.name, p.main_image_url, p.category, p.sale_enabled,
                       MIN(CASE WHEN s.status = 'ON_SALE' THEN s.price END) AS on_sale_price,
                       SUM(CASE WHEN s.status = 'ON_SALE' THEN s.available END) AS on_sale_available,
                       MIN(CASE WHEN s.status = 'SOLD_OUT' THEN s.price END) AS sold_out_price,
                       SUM(CASE WHEN s.status = 'SOLD_OUT' THEN s.available END) AS sold_out_available
                FROM product p JOIN product_sales s ON s.product_id = p.id
                WHERE p.id IN (:ids) AND p.deleted_at IS NULL
                GROUP BY p.id, p.name, p.main_image_url, p.category, p.sale_enabled
                HAVING MIN(CASE WHEN s.status IN ('ON_SALE', 'SOLD_OUT') THEN s.price END) IS NOT NULL
                """,
                Map.of("ids", ids),
                rs -> {
                    long onSalePrice = rs.getLong("on_sale_price");
                    boolean onSale = !rs.wasNull();
                    SalesDisplayStatus state =
                            onSale ? SalesDisplayStatus.ON_SALE : SalesDisplayStatus.SOLD_OUT;
                    String category = rs.getString("category");
                    long id = rs.getLong("id");
                    rows.put(
                            id,
                            new PublicProductItemResponse(
                                    id,
                                    rs.getString("name"),
                                    rs.getString("main_image_url"),
                                    onSale ? onSalePrice : rs.getLong("sold_out_price"),
                                    rs.getInt(onSale ? "on_sale_available" : "sold_out_available"),
                                    state,
                                    rs.getBoolean("sale_enabled"),
                                    category == null ? null : ProductCategory.valueOf(category)));
                });
        return rows;
    }

    private Position decode(String cursor) {
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            Position position =
                    Objects.requireNonNull(
                            mapper.readValue(
                                    Base64.getUrlDecoder().decode(cursor), Position.class));
            // 이전 형식 커서(createdAt, id, price)에는 after 가 없다.
            if (position.fingerprint() == null
                    || position.after() == null
                    || position.after().size() != 2
                    || position.after().contains(null)) {
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

    /**
     * @param after 페이지 마지막 상품의 ES 정렬 값. 최신순은 [생성 시각(밀리초), id], 가격순은 [대표 가격, id]
     */
    private record Position(String fingerprint, List<Long> after) {}
}
