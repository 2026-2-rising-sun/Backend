package com.shoppinglive.catalog.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "CATALOG_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class CatalogSchemaPostgresTest {
    private JdbcTemplate jdbc;
    private String schema;

    @BeforeEach
    void initializeEmptySchema() {
        String url = System.getenv("CATALOG_TEST_POSTGRES_URL");
        String user = System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_USER", "postgres");
        String password =
                System.getenv().getOrDefault("CATALOG_TEST_POSTGRES_PASSWORD", "postgres");
        schema = "catalog_schema_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(url, user, password))
                .execute("CREATE SCHEMA " + schema);
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password);
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    @DisplayName("빈 Catalog DB에는 상품·판매·쿠폰 조회 사본만 생성한다")
    void initializesOnlyReplicaTables() {
        assertThat(
                        jdbc.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE table_schema=?",
                                String.class,
                                schema))
                .containsExactlyInAnyOrder(
                        "product",
                        "product_sales",
                        "coupon_display",
                        "coupon_display_product",
                        "flyway_schema_history");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='product' AND column_name='idempotency_key'",
                                Integer.class,
                                schema))
                .isZero();
    }

    @Test
    @DisplayName("상품 ID는 Core에서 받아야 하며 최초 버전 0은 허용하고 음수는 거절한다")
    void requiresSourceIdentityAndNonnegativeVersion() {
        String columns =
                "name,description,main_image_id,main_image_url,version,created_at,updated_at";
        String values =
                "'검증 상품','상품 설명',123,'/api/commercecore/v1/product-images/123',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP";
        jdbc.update("INSERT INTO product (id," + columns + ") VALUES (9001," + values + ")");
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=9001", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT is_identity FROM information_schema.columns WHERE table_schema=? AND table_name='product' AND column_name='id'",
                                String.class,
                                schema))
                .isEqualTo("NO");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO product ("
                                                + columns
                                                + ") VALUES ("
                                                + values
                                                + ")"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE product SET version=-1 WHERE id=9001"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM product WHERE id=9001", Long.class))
                .isZero();
    }
}
