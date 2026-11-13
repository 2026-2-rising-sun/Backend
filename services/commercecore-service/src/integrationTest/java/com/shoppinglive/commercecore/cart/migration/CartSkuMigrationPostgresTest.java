package com.shoppinglive.commercecore.cart.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class CartSkuMigrationPostgresTest {
    private final String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
    private final String user =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_USER", "postgres");
    private final String password =
            System.getenv().getOrDefault("COMMERCECORE_TEST_POSTGRES_PASSWORD", "postgres");
    private String schema;
    private Connection connection;

    @BeforeEach
    void prepareLegacySchema() throws Exception {
        schema = "cart_migration_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        migration("100").migrate();
        sql(
                "INSERT INTO sales_info(id,product_id,price,status,created_at,updated_at) VALUES(101,501,1000,'ON_SALE',now(),now())");
        sql(
                "INSERT INTO sales_stock(sales_info_id,available,reserved,created_at,updated_at) VALUES(101,10,2,now(),now())");
        sql(
                "INSERT INTO cart_item(id,member_id,product_id,quantity,version,created_at,updated_at) VALUES(11,'member-a',501,2,7,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z'),(12,'member-a',999,8,9,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z')");
    }

    @AfterEach
    void cleanupOwnedSchema() throws Exception {
        if (connection != null) {
            try {
                sql("DROP SCHEMA " + schema + " CASCADE");
            } finally {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("V103은 기존 Sales ID만 연결하고 미매핑 항목과 원본 값은 그대로 보존한다")
    void preservesMappedAndUnavailableLegacyItems() throws Exception {
        migration("103").migrate();

        assertThat(number("SELECT sku_id FROM cart_item WHERE id=11")).isEqualTo(101);
        assertThat(
                        number(
                                "SELECT count(*) FROM cart_item WHERE id=12 AND product_id=999 AND sku_id IS NULL AND quantity=8 AND version=9"))
                .isEqualTo(1);
        assertThat(
                        number(
                                "SELECT count(*) FROM cart_item WHERE id=11 AND product_id=501 AND quantity=2 AND version=7 AND created_at='2026-01-01T00:00:00Z' AND updated_at='2026-01-02T00:00:00Z'"))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM sku")).isEqualTo(1);
        assertThat(number("SELECT available FROM sku WHERE id=101")).isEqualTo(10);
        assertThat(number("SELECT reserved FROM sku WHERE id=101")).isEqualTo(2);
    }

    @Test
    @DisplayName("동일 상품에 SKU가 여러 개여도 기존 기본 SKU에만 연결하고 SKU별 담기를 허용한다")
    void usesPreservedSalesIdInsteadOfArbitraryProductSku() throws Exception {
        migration("102").migrate();
        sql(
                "INSERT INTO sku(id,product_id,price,available,reserved,sale_status,version,snapshot_version,product_name_snapshot,created_at,updated_at) VALUES(202,501,2000,5,0,'ON_SALE',0,0,'다른 옵션',now(),now())");
        migration("103").migrate();

        assertThat(number("SELECT sku_id FROM cart_item WHERE id=11")).isEqualTo(101);
        sql(
                "INSERT INTO cart_item(id,member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES(13,'member-a',501,202,5,0,now(),now())");
        assertThat(
                        number(
                                "SELECT count(*) FROM cart_item WHERE member_id='member-a' AND product_id=501"))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                sql(
                                        "INSERT INTO cart_item(id,member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES(14,'member-a',501,101,1,0,now(),now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_cart_member_sku");
    }

    @Test
    @DisplayName("미매핑 레거시 중복과 잘못된 수량을 PostgreSQL 제약으로 막는다")
    void enforcesLegacyUniquenessAndQuantityConstraints() throws Exception {
        migration("103").migrate();

        assertThatThrownBy(
                        () ->
                                sql(
                                        "INSERT INTO cart_item(id,member_id,product_id,quantity,version,created_at,updated_at) VALUES(13,'member-a',999,1,0,now(),now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_cart_member_legacy_product");
        assertThatThrownBy(() -> sql("UPDATE cart_item SET quantity=0 WHERE id=11"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_cart_quantity");
        assertThat(number("SELECT quantity FROM cart_item WHERE id=11")).isEqualTo(2);
    }

    private Flyway migration(String target) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(true)
                .load();
    }

    private void sql(String statement) throws SQLException {
        try (var query = connection.createStatement()) {
            query.execute(statement);
        }
    }

    private long number(String statement) throws SQLException {
        try (var query = connection.createStatement();
                var result = query.executeQuery(statement)) {
            result.next();
            return result.getLong(1);
        }
    }
}
