package com.shoppinglive.commercecore.sku;

import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** 실제 SKU 테이블에만 저장하는 통합 테스트 준비 데이터. */
public final class SkuFixtures {
    private SkuFixtures() {}

    public static long insert(
            JdbcTemplate jdbc,
            long productId,
            long price,
            SalesStatus status,
            int available,
            int reserved) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "INSERT INTO sku(product_id,price,sale_status,available,reserved,version,snapshot_version,product_name_snapshot,created_at,updated_at) VALUES(?,?,?,?,?,0,1,'테스트 상품',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                                    new String[] {"id"});
                    statement.setLong(1, productId);
                    statement.setLong(2, price);
                    statement.setString(
                            3, status == SalesStatus.SOLD_OUT ? "ON_SALE" : status.name());
                    statement.setInt(4, available);
                    statement.setInt(5, reserved);
                    return statement;
                },
                key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public static void product(JdbcTemplate jdbc, long id, String seller) {
        jdbc.update(
                "INSERT INTO product(id,name,description,main_image_id,seller_id,sale_enabled,version,created_at,updated_at) VALUES(?,'테스트 상품','설명',1,?,false,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,
                seller);
    }
}
