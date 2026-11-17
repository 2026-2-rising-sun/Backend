package com.shoppinglive.catalog.sales.repository;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = ".+")
class SkuSalesMigrationPostgresTest {
    @Test
    @DisplayName("실제 PostgreSQL V3 이행은 기존 사본 값·버전을 보존하고 같은 상품의 복수 SKU를 분리한다")
    void migratesReplicaWithoutCollapsingSkus() throws Exception {
        String schema = "sku_replica_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL"),
                user = System.getenv("CATALOG_TEST_POSTGRES_USER"),
                password = System.getenv("CATALOG_TEST_POSTGRES_PASSWORD");
        try (var c = DriverManager.getConnection(url, user, password);
                var s = c.createStatement()) {
            try {
                Flyway.configure()
                        .dataSource(url, user, password)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("2")
                        .load()
                        .migrate();
                s.execute("SET search_path TO " + schema);
                s.executeUpdate(
                        "INSERT INTO product_sales(product_id,sales_id,price,status,available,version,updated_at) VALUES(1,11,19000,'PRIVATE',7,10,CURRENT_TIMESTAMP)");
                Flyway.configure()
                        .dataSource(url, user, password)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .load()
                        .migrate();
                try (var row = s.executeQuery("SELECT * FROM product_sales WHERE sales_id=11")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong("product_id")).isEqualTo(1);
                    assertThat(row.getLong("price")).isEqualTo(19000);
                    assertThat(row.getString("status")).isEqualTo("PRIVATE");
                    assertThat(row.getInt("available")).isEqualTo(7);
                    assertThat(row.getLong("version")).isEqualTo(10);
                }
                s.executeUpdate(
                        "INSERT INTO product_sales(product_id,sales_id,price,status,available,version,updated_at) VALUES(1,12,21000,'SOLD_OUT',0,1,CURRENT_TIMESTAMP)");
                s.executeUpdate("UPDATE product_sales SET version=2,price=22000 WHERE sales_id=12");
                try (var row =
                        s.executeQuery(
                                "SELECT count(*),sum(version) FROM product_sales WHERE product_id=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(2);
                    assertThat(row.getInt(2)).isEqualTo(12);
                }
            } finally {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
