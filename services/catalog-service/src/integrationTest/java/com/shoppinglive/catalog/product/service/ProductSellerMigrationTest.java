package com.shoppinglive.catalog.product.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

class ProductSellerMigrationTest {
    @Test
    @DisplayName("빈 Catalog 스키마는 원본 ID·이미지 파일·멱등키 없이 상품·판매·쿠폰 사본만 보관한다")
    void emptySchemaKeepsOnlyReadModels() throws Exception {
        String url =
                "jdbc:h2:mem:catalog_schema_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", "");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO product(id,name,description,main_image_id,main_image_url,version,created_at,updated_at) VALUES (8,'상품','설명',99,'/api/commercecore/v1/product-images/99',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            try (var rs = s.executeQuery("SELECT id,version,seller_id,category FROM product")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("id")).isEqualTo(8);
                assertThat(rs.getLong("version")).isZero();
                assertThat(rs.getString("seller_id")).isNull();
                assertThat(rs.getString("category")).isNull();
            }
            assertThatThrownBy(() -> s.executeQuery("SELECT * FROM product_image"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeQuery("SELECT idempotency_key FROM product"))
                    .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO product(name) VALUES ('ID 없음')"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
