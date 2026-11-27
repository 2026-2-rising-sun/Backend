package com.shoppinglive.catalog.search;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Catalog DB 사본으로 검색 문서를 만든다. 공개 목록과 같은 기준으로, 삭제되지 않았고 공개 SKU 가 있는 상품만 문서가 된다. */
public class ProductSearchSource {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductSearchSource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 문서가 되는 상품만 담는다. 없는 id 는 ES 에서 지워야 하는 상품이다.
     */
    public Map<Long, ProductSearchDocument> load(Collection<Long> productIds) {
        Map<Long, ProductSearchDocument> documents = new HashMap<>();
        if (productIds.isEmpty()) return documents;
        jdbc.query(
                """
                SELECT p.id, p.name, p.description, p.category, p.seller_id, p.created_at,
                       COALESCE(MAX(v.score), 0) AS view_score,
                       MIN(CASE WHEN s.status = 'ON_SALE' THEN s.price END) AS on_sale_price,
                       MIN(CASE WHEN s.status = 'SOLD_OUT' THEN s.price END) AS sold_out_price
                FROM product p JOIN product_sales s ON s.product_id = p.id
                LEFT JOIN product_view_score v ON v.product_id = p.id
                WHERE p.id IN (:ids) AND p.deleted_at IS NULL
                GROUP BY p.id, p.name, p.description, p.category, p.seller_id, p.created_at
                HAVING MIN(CASE WHEN s.status IN ('ON_SALE', 'SOLD_OUT') THEN s.price END) IS NOT NULL
                """,
                Map.of("ids", productIds),
                rs -> {
                    long onSale = rs.getLong("on_sale_price");
                    boolean soldOut = rs.wasNull();
                    long id = rs.getLong("id");
                    documents.put(
                            id,
                            new ProductSearchDocument(
                                    id,
                                    rs.getString("name"),
                                    rs.getString("description"),
                                    rs.getString("category"),
                                    rs.getString("seller_id"),
                                    soldOut ? rs.getLong("sold_out_price") : onSale,
                                    soldOut,
                                    rs.getTimestamp("created_at").toInstant(),
                                    rs.getLong("view_score")));
                });
        return documents;
    }
}
