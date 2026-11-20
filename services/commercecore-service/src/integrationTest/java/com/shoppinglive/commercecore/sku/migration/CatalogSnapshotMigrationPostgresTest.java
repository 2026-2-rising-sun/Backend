package com.shoppinglive.commercecore.sku.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class CatalogSnapshotMigrationPostgresTest {
    @Test
    @DisplayName("V108의 기존 SKU 가격·예약·시각을 보존하고 V109에서 최초 재고 적재 대상으로 만든다")
    void preservesExistingRowsAndMarksInitialInventoryDirty() {
        String schema = "catalog_b_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        System.getenv("COMMERCECORE_TEST_POSTGRES_USER"),
                        System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        try {
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("108")
                    .load()
                    .migrate();
            var jdbc = new JdbcTemplate(source);
            Instant time = Instant.parse("2026-11-18T12:34:56.123456Z");
            jdbc.update(
                    "INSERT INTO product(id,name,description,main_image_id,seller_id,sale_enabled,version,created_at,updated_at) VALUES(7001,'기존 상품','설명',1,'seller',true,5,?,?)",
                    Timestamp.from(time),
                    Timestamp.from(time));
            jdbc.update(
                    "INSERT INTO sku(id,product_id,price,available,reserved,sale_status,snapshot_version,product_name_snapshot,option_label,created_at,updated_at) VALUES(7101,7001,4321,8,2,'ON_SALE',12,'기존 상품','검정/M',?,?)",
                    Timestamp.from(time),
                    Timestamp.from(time));
            Flyway.configure()
                    .dataSource(source)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT price,available,reserved,snapshot_version,metadata_version,metadata_product_version,inventory_version,inventory_published_version FROM sku WHERE id=7101"))
                    .containsEntry("price", 4321L)
                    .containsEntry("available", 8)
                    .containsEntry("reserved", 2)
                    .containsEntry("snapshot_version", 12L)
                    .containsEntry("metadata_version", 0L)
                    .containsEntry("metadata_product_version", 5L)
                    .containsEntry("inventory_version", 0L)
                    .containsEntry("inventory_published_version", -1L);
            assertThat(
                            jdbc.queryForObject(
                                            "SELECT inventory_changed_at FROM sku WHERE id=7101",
                                            Timestamp.class)
                                    .toInstant())
                    .isEqualTo(time);
            assertThat(
                            jdbc.queryForObject(
                                            "SELECT metadata_updated_at FROM sku WHERE id=7101",
                                            Timestamp.class)
                                    .toInstant())
                    .isEqualTo(time);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM sku WHERE inventory_version>inventory_published_version",
                                    Long.class))
                    .isEqualTo(1);
        } finally {
            new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
