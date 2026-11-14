package com.shoppinglive.commercecore.support;

import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.commercecore.sku.dto.SkuView;
import com.shoppinglive.commercecore.sku.entity.SkuSaleStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/** 기존 Sales-only 준비 데이터를 명시적으로 SKU 거래 fixture로 이행한다. 운영 경로에는 포함되지 않는다. */
@Component
public class LegacyOrderTradeFixture {
    private final JdbcTemplate jdbc;
    private final ProductLookup products;

    public LegacyOrderTradeFixture(JdbcTemplate jdbc, ProductLookup products) {
        this.jdbc = jdbc;
        this.products = products;
    }

    public void prepare() {
        new ResourceDatabasePopulator(new ClassPathResource("cart/sku-fixture.sql"))
                .execute(jdbc.getDataSource());
        jdbc.update("DELETE FROM sku");
        addMissing();
    }

    public void addMissing() {
        for (var row :
                jdbc.queryForList(
                        "SELECT s.id,s.product_id,s.price,s.status,t.available,t.reserved FROM sales_info s JOIN sales_stock t ON t.sales_info_id=s.id")) {
            long id = ((Number) row.get("id")).longValue();
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=?", Long.class, id) > 0)
                continue;
            long product = ((Number) row.get("product_id")).longValue();
            var display = products.findProduct(product);
            if (display.isPresent()) {
                var p = display.get();
                jdbc.update(
                        "INSERT INTO product(id,name,description,main_image_id,seller_id,sale_enabled,version,created_at,updated_at) SELECT ?,?,'주문 테스트 fixture',1,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS (SELECT 1 FROM product WHERE id=?)",
                        product,
                        p.name(),
                        p.sellerId(),
                        p.saleEnabled(),
                        product);
                jdbc.update(
                        "UPDATE product SET name=?,seller_id=?,sale_enabled=?,deleted_at=NULL WHERE id=?",
                        p.name(),
                        p.sellerId(),
                        p.saleEnabled(),
                        product);
            }
            String status = row.get("status").toString();
            jdbc.update(
                    "INSERT INTO sku(id,product_id,price,available,reserved,sale_status,version,snapshot_version,product_name_snapshot,created_at,updated_at) VALUES (?,?,?,?,?,?,0,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    id,
                    product,
                    row.get("price"),
                    row.get("available"),
                    row.get("reserved"),
                    status.equals("SOLD_OUT") ? "ON_SALE" : status,
                    display.map(p -> p.name()).orElse("테스트 상품"));
        }
    }

    public SkuView sku(long id) {
        return jdbc.queryForObject(
                "SELECT * FROM sku WHERE id=?",
                (r, n) ->
                        new SkuView(
                                r.getLong("id"),
                                r.getLong("product_id"),
                                r.getLong("price"),
                                r.getInt("available"),
                                r.getInt("reserved"),
                                SkuSaleStatus.valueOf(r.getString("sale_status")),
                                r.getLong("version")),
                id);
    }
}
