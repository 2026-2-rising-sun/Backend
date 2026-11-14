package com.shoppinglive.commercecore.sku.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class SkuMigrationPostgresTest {
    @Test
    @DisplayName("V102는 Sales ID·가격·예약·버전·중지 의도를 보존하고 신규 ID 충돌을 막는다")
    void preservesLegacyValuesAndAdvancesIdentity() throws Exception {
        withSchema(
                connection -> {
                    try (var sql = connection.createStatement()) {
                        sql.executeUpdate(
                                """
                        INSERT INTO sales_info(id,product_id,price,status,version,snapshot_version,
                            product_name_snapshot,created_at,updated_at)
                        VALUES(9001,51,19000,'SOLD_OUT',7,10,'상품 A',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                              (9002,52,21000,'PRIVATE',8,11,'상품 B',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
                        sql.executeUpdate(
                                """
                        INSERT INTO sales_stock(sales_info_id,available,reserved,created_at,updated_at)
                        VALUES(9001,0,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
                              (9002,5,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
                        migrate(connection, "V102__sku.sql");
                        try (var rows = sql.executeQuery("SELECT * FROM sku ORDER BY id")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong("id")).isEqualTo(9001);
                            assertThat(rows.getLong("price")).isEqualTo(19000);
                            assertThat(rows.getInt("available")).isZero();
                            assertThat(rows.getInt("reserved")).isEqualTo(2);
                            assertThat(rows.getString("sale_status")).isEqualTo("ON_SALE");
                            assertThat(rows.getLong("version")).isEqualTo(7);
                            assertThat(rows.getLong("snapshot_version")).isEqualTo(10);
                            assertThat(rows.getString("product_name_snapshot")).isEqualTo("상품 A");
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getString("sale_status")).isEqualTo("PRIVATE");
                            assertThat(rows.getInt("available")).isEqualTo(5);
                        }
                        try (var rows =
                                sql.executeQuery(
                                        """
                        INSERT INTO sku(product_id,price,available,reserved,sale_status,
                            product_name_snapshot,created_at,updated_at)
                        VALUES(51,20000,0,0,'READY','새 SKU',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        RETURNING id
                        """)) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getLong(1)).isGreaterThan(9002);
                        }
                    }
                });
    }

    @Test
    @DisplayName("재고 행이 누락된 Sales는 V102에서 조용히 버리지 않고 이행을 거절한다")
    void refusesSalesWithoutStock() throws Exception {
        withSchema(
                connection -> {
                    try (var sql = connection.createStatement()) {
                        sql.executeUpdate(
                                """
                        INSERT INTO sales_info(product_id,price,status,created_at,updated_at)
                        VALUES(51,19000,'READY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
                        connection.setAutoCommit(false);
                        assertThatThrownBy(() -> migrate(connection, "V102__sku.sql"))
                                .hasRootCauseInstanceOf(SQLException.class);
                        connection.rollback();
                        connection.setAutoCommit(true);
                        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM sales_info")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getInt(1)).isEqualTo(1);
                        }
                    }
                });
    }

    @Test
    @DisplayName("양수 재고의 기존 SOLD_OUT을 임의 판매 재개하지 않고 V102를 거절한다")
    void refusesInconsistentLegacySoldOutState() throws Exception {
        withSchema(
                connection -> {
                    try (var sql = connection.createStatement()) {
                        sql.executeUpdate(
                                """
                        INSERT INTO sales_info(id,product_id,price,status,created_at,updated_at)
                        VALUES(9001,51,19000,'SOLD_OUT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
                        sql.executeUpdate(
                                """
                        INSERT INTO sales_stock(sales_info_id,available,reserved,created_at,updated_at)
                        VALUES(9001,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """);
                        connection.setAutoCommit(false);
                        assertThatThrownBy(() -> migrate(connection, "V102__sku.sql"))
                                .hasRootCauseInstanceOf(SQLException.class);
                        connection.rollback();
                        connection.setAutoCommit(true);
                        try (var row =
                                sql.executeQuery("SELECT status FROM sales_info WHERE id=9001")) {
                            assertThat(row.next()).isTrue();
                            assertThat(row.getString(1)).isEqualTo("SOLD_OUT");
                        }
                    }
                });
    }

    private void withSchema(SqlWork work) throws Exception {
        try (Connection connection =
                DriverManager.getConnection(
                        System.getenv("COMMERCECORE_TEST_POSTGRES_URL"),
                        System.getenv("COMMERCECORE_TEST_POSTGRES_USER"),
                        System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"))) {
            String schema = "sku_migration_" + UUID.randomUUID().toString().replace("-", "");
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE SCHEMA " + schema);
                try {
                    connection.setSchema(schema);
                    migrate(connection, "V1__commerce_baseline.sql");
                    migrate(connection, "V16__sales_snapshot_version.sql");
                    migrate(connection, "V19__sales_product_name_snapshot.sql");
                    work.run(connection);
                } finally {
                    if (!connection.getAutoCommit()) connection.rollback();
                    connection.setAutoCommit(true);
                    connection.setSchema("public");
                    sql.execute("DROP SCHEMA " + schema + " CASCADE");
                }
            }
        }
    }

    private static void migrate(Connection connection, String name) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + name));
    }

    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
