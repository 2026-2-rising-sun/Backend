package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class ProductSchemaPostgresTest {
    @Test
    @DisplayName("빈 Core 상품 스키마는 카테고리·판매자 null과 이미지 FK·멱등키 제약을 보존한다")
    void freshProductSchemaPreservesOriginalConstraints() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                System.getenv("COMMERCECORE_TEST_POSTGRES_URL"),
                                System.getenv("COMMERCECORE_TEST_POSTGRES_USER"),
                                System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
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
        }
    }
}
