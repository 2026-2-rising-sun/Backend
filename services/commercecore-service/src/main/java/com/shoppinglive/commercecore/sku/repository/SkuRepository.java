package com.shoppinglive.commercecore.sku.repository;

import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.entity.SkuSaleStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SkuRepository {
    private static final String FIELDS =
            "id, product_id, price, available, reserved, sale_status, version";
    private static final RowMapper<SkuView> VIEW =
            (rs, row) ->
                    new SkuView(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getLong("price"),
                            rs.getInt("available"),
                            rs.getInt("reserved"),
                            SkuSaleStatus.valueOf(rs.getString("sale_status")),
                            rs.getLong("version"));
    private final NamedParameterJdbcTemplate jdbc;

    public SkuRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SkuView> findBySkuIds(Collection<Long> ids, boolean lock) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(
                "SELECT "
                        + FIELDS
                        + " FROM sku WHERE id IN (:ids) ORDER BY id"
                        + (lock ? " FOR UPDATE" : ""),
                Map.of("ids", ids),
                VIEW);
    }

    public List<SkuView> findByProductId(Long productId) {
        return jdbc.query(
                "SELECT " + FIELDS + " FROM sku WHERE product_id=:productId ORDER BY id",
                Map.of("productId", productId),
                VIEW);
    }

    public long create(Long productId, long price, int available, String productName) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                """
                INSERT INTO sku(product_id,price,available,reserved,sale_status,version,
                    snapshot_version,product_name_snapshot,created_at,updated_at)
                VALUES(:productId,:price,:available,0,'READY',0,1,:productName,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("price", price)
                        .addValue("available", available)
                        .addValue("productName", productName),
                key,
                new String[] {"id"});
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public int changePrice(Long id, long price) {
        return update("price=:value", "price<>:value", id, price);
    }

    public int changeStatus(Long id, SkuSaleStatus status) {
        return jdbc.update(
                """
                UPDATE sku SET sale_status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP
                WHERE id=:id AND sale_status<>:status
                """,
                Map.of("id", id, "status", status.name()));
    }

    public int adjustAvailable(Long id, int delta) {
        return update(
                "available=available+:value",
                "CAST(available AS BIGINT)+:value BETWEEN 0 AND 2147483647",
                id,
                delta);
    }

    public int reserve(Long id, int quantity) {
        return update(
                "available=available-:value,reserved=reserved+:value",
                "sale_status='ON_SALE' AND available>=:value"
                        + " AND CAST(reserved AS BIGINT)+:value<=2147483647",
                id,
                quantity);
    }

    public int consume(Long id, int quantity) {
        return update("reserved=reserved-:value", "reserved>=:value", id, quantity);
    }

    public int release(Long id, int quantity) {
        return update(
                "reserved=reserved-:value,available=available+:value",
                "reserved>=:value AND CAST(available AS BIGINT)+:value<=2147483647",
                id,
                quantity);
    }

    private int update(String assignment, String condition, Long id, long value) {
        return jdbc.update(
                "UPDATE sku SET "
                        + assignment
                        + ",version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND "
                        + condition,
                Map.of("id", id, "value", value));
    }
}
