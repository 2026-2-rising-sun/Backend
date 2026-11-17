package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductSchemaMigrationTest {
    protected String databaseUrl() {
        return "jdbc:h2:mem:product_migration_"
                + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    }

    protected String databaseUser() {
        return "sa";
    }

    protected String databasePassword() {
        return "";
    }

    @Test
    @DisplayName("V101은 기존 판매 상태와 제약을 보존하고 신규 판매 준비·소프트 삭제·이미지 논리 참조로 이행한다")
    void freshProductSchemaPreservesOriginalConstraints() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                databaseUrl(), databaseUser(), databasePassword());
                var s = c.createStatement()) {
            String schema = "product_schema_" + UUID.randomUUID().toString().replace("-", "");
            s.execute("CREATE SCHEMA " + schema);
            c.setSchema(schema);
            ScriptUtils.executeSqlScript(
                    c, new ClassPathResource("db/migration/V100__products.sql"));
            s.executeUpdate(
                    "INSERT INTO product_image(storage_key,content_type,size_bytes,width,height,created_at,updated_at) VALUES ('fixture.png','image/png',100,10,10,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.executeUpdate(
                    "INSERT INTO product(name,description,main_image_id,idempotency_key,created_at,updated_at) VALUES ('원본','설명',1,'key',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            try (var rs = s.executeQuery("SELECT seller_id,category,version FROM product")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("seller_id")).isNull();
                assertThat(rs.getString("category")).isNull();
                assertThat(rs.getLong("version")).isZero();
            }
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            "INSERT INTO product(name,description,main_image_id,idempotency_key,created_at,updated_at) VALUES ('다른 상품','설명',1,'key',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            "INSERT INTO product(name,description,main_image_id,created_at,updated_at) VALUES ('이미지 없음','설명',999,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            ScriptUtils.executeSqlScript(
                    c, new ClassPathResource("db/migration/V101__product_sale_control.sql"));
            try (var rs =
                    s.executeQuery(
                            "SELECT sale_enabled,deleted_at FROM product WHERE idempotency_key='key'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("sale_enabled")).isTrue();
                assertThat(rs.getObject("deleted_at")).isNull();
            }
            s.executeUpdate(
                    "INSERT INTO product(name,description,main_image_id,idempotency_key,created_at,updated_at) VALUES ('신규','설명',999,'new',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            try (var rs =
                    s.executeQuery(
                            "SELECT sale_enabled FROM product WHERE idempotency_key='new'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isFalse();
            }
            try (var rs =
                    s.executeQuery(
                            "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='"
                                    + schema
                                    + "' AND table_name='product' AND constraint_type='FOREIGN KEY'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            "UPDATE product SET deleted_at=CURRENT_TIMESTAMP WHERE idempotency_key='key'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            "UPDATE product SET main_image_id=NULL WHERE idempotency_key='new'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.executeUpdate(
                                            "UPDATE product SET idempotency_key='key' WHERE idempotency_key='new'"))
                    .isInstanceOf(SQLException.class);
            c.setSchema("public");
            s.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
