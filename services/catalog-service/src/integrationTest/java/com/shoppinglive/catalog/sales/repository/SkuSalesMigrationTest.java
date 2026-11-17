package com.shoppinglive.catalog.sales.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkuSalesMigrationTest {
    @Test
    @DisplayName("Catalog V3는 기존 사본 값을 보존하고 같은 상품의 다른 SKU를 추가할 수 있다 (H2)")
    void preservesReplicaValuesWhenChangingPrimaryKey() throws Exception {
        String url =
                "jdbc:h2:mem:sku_replica_"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var sql = connection.createStatement()) {
            sql.executeUpdate(
                    """
                    INSERT INTO product_sales(product_id,sales_id,price,status,available,version,updated_at)
                    VALUES(1,11,19000,'PRIVATE',7,10,CURRENT_TIMESTAMP)
                    """);
            Flyway.configure().dataSource(url, "sa", "").load().migrate();
            try (var row = sql.executeQuery("SELECT * FROM product_sales WHERE sales_id=11")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("product_id")).isEqualTo(1);
                assertThat(row.getLong("price")).isEqualTo(19000);
                assertThat(row.getString("status")).isEqualTo("PRIVATE");
                assertThat(row.getInt("available")).isEqualTo(7);
                assertThat(row.getLong("version")).isEqualTo(10);
            }
            sql.executeUpdate(
                    """
                    INSERT INTO product_sales(product_id,sales_id,price,status,available,version,updated_at)
                    VALUES(1,12,21000,'SOLD_OUT',0,1,CURRENT_TIMESTAMP)
                    """);
            try (var row =
                    sql.executeQuery("SELECT COUNT(*) FROM product_sales WHERE product_id=1")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(2);
            }
        }
    }
}
